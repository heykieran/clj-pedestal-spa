(ns server.auth.google
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [cheshire.core :as cheshire]
    [buddy.sign.jwt :as jwt]
    [buddy.sign.jws :as jws]
    [buddy.core.keys :as keys]
    [java-time :as time]
    [config.server :as server-config]
    [server.auth.utils :as auth-utils]
    [server.auth.debug :as debug-sign]))

(defn load-jwk-agent-info
  [state-of-agent jwk-url & [force]]
  (if
    (or (nil? (get state-of-agent :last))
        force
        (time/after?
          (time/instant)
          (time/plus (get state-of-agent :last) (time/minutes 30))))
    (do
      (log/info "Agent loading JWK info from Google/Local")
      (let
        [cache
         {:last
          (time/instant)
          :jwk
          (cond->
            (-> (slurp jwk-url)
                (cheshire.core/parse-string
                  true)
                :keys)
            server-config/debug-local-jwt
            (conj (debug-sign/debug-local-jwt-public
                    server-config/debug-local-jwt)))}]
        (log/info "Agent loaded JWK info from Google/Local")
        cache))
    state-of-agent))

(defonce jwk-cache-agent
         (agent {:last nil :jwk nil}
                :error-handler
                 (fn[a e]
                   (log/error "Exception in cache-agent " e))))

(send-off jwk-cache-agent load-jwk-agent-info server-config/google-jwk-url)

(defn get-valid-user-id-from-header
  [google-header-assertion valid-map & [minutes-offset]]
  (letfn
    [(get-user-id-with-validation
       [r-val v-map]
       (log/info "Checking token values for validity:" r-val
                 "against" v-map)
       (when
         (every?
           (fn [kw]
             (= (get r-val kw) (get v-map kw)))
           (keys v-map))
         (log/info "token is valid, email address is" (get r-val :email))
         {:alloc-auth/user-id
          (auth-utils/get-id-from-email-address
            (get r-val :email))
          :alloc-auth/token-type :google
          :alloc-auth/token r-val}))]
    (let
      [decoded-header
       (try
         (jws/decode-header google-header-assertion)
         (catch clojure.lang.ExceptionInfo e
           (throw
             (ex-info "Unable to decode assertion header as jws"
                      (merge
                        (ex-data e)
                        {:ex-message (.getMessage ^Throwable e)
                         :ex-cause   (.getCause ^Throwable e)})
                      e)))
         (catch Exception e
           (throw
             (ex-info "Unable to decode assertion header as jws"
                      {:ex-message (.getMessage ^Throwable e)
                       :ex-cause (.getCause ^Throwable e)}
                      e))))]
      (log/info "Successfully decoded header assertion using :kid" (:kid decoded-header))
      (log/info "Cache agent contains"
                (if-let
                  [jwks (some-> (deref jwk-cache-agent) :jwk)]
                  (str (count jwks) " entries with kid's " (mapv :kid jwks))
                  "no entries."))
      (if-let
        [public-key-jwt
         (some
           (fn [poss]
             (when
               (= (:kid poss)
                  (:kid decoded-header))
               poss))
           (get (deref jwk-cache-agent) :jwk))]
        (let
          [alg (keyword (str/lower-case (:alg public-key-jwt)))]
          (try
            (->
              (jwt/unsign
                google-header-assertion
                (keys/jwk->public-key public-key-jwt)
                {:alg alg
                 :now (time/minus (time/instant) (time/minutes (or minutes-offset 0)))})
              (get-user-id-with-validation valid-map))
            (catch Exception e
              (log/error
                "Google header validation problem, "
                (pr-str (ex-data e))
                "using public key"
                (pr-str public-key-jwt)))))
        (do
          (log/error "No matching :kid entry found in cache.")
          (throw
            (let [e (Exception.
                      (str "No matching :kid ("
                           (pr-str decoded-header)
                           ") entry found in cache."))]
              (ex-info "Bad jws."
                     (merge
                       (ex-data e)
                       {:ex-message (.getMessage ^Throwable e)
                        :ex-cause   (.getCause ^Throwable e)})
                     e))))))))
