(ns server.auth.header
  (:require
    [io.pedestal.interceptor :as interceptor]
    [io.pedestal.interceptor.error :refer [error-dispatch]]
    [ring.util.response :as ring-response]
    [ring.middleware.cookies :as cookies]
    [buddy.auth.protocols :as proto]
    [buddy.auth.http :as http]
    [buddy.auth.backends.token :as token]
    [buddy.auth :as auth]
    [buddy.sign.jws :as jws]
    [buddy.auth.backends :as auth.backends]
    [buddy.auth.middleware :as auth.middleware]
    [server.auth.google :as google]
    [hiccup.core :refer [html]]
    [clojure.tools.logging :as log]
    [java-time :as t]
    [clojure.string :as str]
    [utils.utils :as gen-utils]
    [server.auth.data :as auth-data]
    [config.server :as server-config]))

(defn token-authfn
  [request token]
  (log/info "Attempting to authorize using token with JWT header: "
            (some-> token (jws/decode-header)))
  (when token
    (let
      [validity-map
       (if (= "local" (:kid (jws/decode-header token)))
         {:aud "local"
          :iss "local"}
         {:aud server-config/google-jwt-audience
          :iss "https://cloud.google.com/iap"})]
      (try
        (when-let
          [user-id
           (google/get-valid-user-id-from-header
             token
             validity-map
             240)]
          (log/info "Successfully retrieved user ID using token " user-id)
          user-id)
        (catch clojure.lang.ExceptionInfo e
          (log/info
            (str "Exception thrown while retrieving user from token. Message: "
                 (.getMessage ^Throwable e) ", Data: " (pr-str (ex-data e))))
          (throw e))))))

;; ================================================================

(defn- parse-embedded-header
  [request token-names]
  ;; If there is a session and there is an :identity
  ;; return a vector of [:session <identity>]
  ;; If not, look for a token name in the header that
  ;; is expected to contain a signed jwt identity
  ;; Fot the token name(s), we're given a token name
  ;; (or a vector of token names) and will try to extract
  ;; from the headers the value of it (or the first matching
  ;; one from the vector) and return that value.
  ;; Therefore, there is an order of
  ;; precedence and any debug token names should be at the
  ;; end of the vector.
  (log/info "Parsing embedded header, looking for session or token(s):"
            (pr-str token-names) "."
            "session"
            (if (:identity (:session request))
              "contains identity."
              "has no identity."))
  (if-let
    ;; if they're a session identity, tag and return it
    [session-identity (:identity (:session request))]
    [:session session-identity]
    ;; if not, find the value of the first matching header,
    ;; then tag and return it.
    (let
      [token-names
       (if (coll? token-names) token-names [token-names])
       token-name
       (or
         (some
           (fn[^String token-h-name]
             (if (some
                   (fn[[h-name _]]
                     (if (.equalsIgnoreCase
                           token-h-name
                           ^String (name h-name))
                       h-name))
                   (:headers request))
               token-h-name))
           token-names)
         (first token-names))]
      [:token (http/-get-header request token-name)])))

(defn jws-embedded-backend
  [{:keys [authfn unauthorized-handler options token-names on-error]}]
  {:pre [(ifn? authfn)]}
  (reify
    proto/IAuthentication
    (-parse [_ request]
      (parse-embedded-header request token-names))
    (-authenticate [_ request [auth-type session-or-token]]
      (try
        ;; parse-embedded-header will return a 2-vector
        ;; with either :session of :token in the first
        ;; position indicating how the identity is/should be
        ;; determined.
        (case auth-type
          :token
          ;; if we've a token, attempt to identify the user using it
          (authfn request session-or-token)
          :session
          ;; if we've a session with an identity, just accept it
          session-or-token)
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (when (fn? on-error)
              (on-error request data))
            nil))))

    proto/IAuthorization
    (-handle-unauthorized [_ request metadata]
      (if unauthorized-handler
        (unauthorized-handler request metadata)
        (#'token/handle-unauthorized-default request)))))

(defn jws-embedded
  "Create an instance of the jws (signed JWT)
  based authentication backend where the signed token
  is embedded in its own header rather in the :authorization
  header.

  This backend also implements authorization workflow
  with some defaults. This means that you can provide
  your own unauthorized-handler hook if the default one
  does not satisfy you."
  ([] (jws-embedded-backend nil))
  ([opts] (jws-embedded-backend opts)))

;; ================================================================

(def alloc-auth-google-header-token-auth-backend
  (jws-embedded
    {:authfn      token-authfn
     :token-names ["x-goog-iap-jwt-assertion" "x-debug-token"]
     :on-error
                  (fn [request ex-data]
                    (log/error
                      "Request to"
                      (:uri request)
                      "threw exception: "
                      ex-data))
     :unauthorized-handler
                  (fn unauthorized-handler
                    [request metadata]
                    (let [{user         :user
                           roles        :roles
                           required     :required
                           user-session :user-session}
                          (get-in metadata [:details :request])
                          user-response-session
                          (keyword
                            (if user (name user) "anonymous")
                            (if user-session (name user-session) "anonymous"))
                          error-message
                          (str "NOT AUTHORIZED (HEADER): In unauthenticated handler for "
                               "uri: " (pr-str (:uri request)) ". "
                               "user: " (pr-str user) ", "
                               "roles: " (pr-str roles) ", "
                               "required roles: " (pr-str required) ". "
                               "session-id: " (pr-str user-response-session) ".")]
                      (log/error
                       error-message))
                    (cond
                      ;; If request is authenticated, raise 403 instead
                      ;; of 401 (because user is authenticated but permission
                      ;; denied is raised).
                      (auth/authenticated? request)
                      (-> (ring-response/response
                            {:reason
                             (str "Header authenticated, but not authorized for access to "
                                  (some-> metadata :details :request :path) ". "
                                  "Metadata : " (pr-str metadata))})
                          (assoc :status 403))
                      ;; In other cases, respond with a 401.
                      :else
                      (let [current-url (:uri request)]
                        (->
                          (ring-response/response
                            {:reason "Unauthorized"})
                          (assoc :status 401)
                          (ring-response/header "WWW-Authenticate" "tg-auth, type=1")))))}))

(defn
  alloc-auth-google-header-auth-authentication-interceptor
  [backend]
  (interceptor/interceptor
    {:name  ::alloc-auth-google-header-token-authenticate
     :enter (fn [ctx]
              (log/info "Entering Google IAP header auth interceptor.")
              (-> ctx
                  (assoc
                    :auth/backend
                    backend)
                  (update
                    :request
                    auth.middleware/authentication-request
                    backend)))}))

(defn get-user-session-identifiers-for-user
  [logged-in-users user-id]
  (seq
    (keep
      (fn[[[logged-in-user logged-in-user-session marker :as logged-in-user-key]
           {logged-in-user-id :alloc-auth/user-id
            session-id :alloc-auth/user-session
            :as logged-in-user-info}]]
        (if
          (= logged-in-user logged-in-user-id user-id)
          logged-in-user-info))
      logged-in-users)))

(defn check-and-attach-session-id-to-login-info
  "For interactions that are not associated with a session, check
  if we should attach a response log session"
  [{request-user-id :alloc-auth/user-id
    request-session-id :alloc-auth/user-session
    :as request-login-info} requested-response-session-id]
  (log/info "Requested to set response session-id to"
            requested-response-session-id
            "for user-id"
            (pr-str request-user-id)
            "with request session-id of"
            request-session-id)
  (let
    [user-with-current-session?
     (and request-user-id request-session-id)
     new-login-info
     (cond
       user-with-current-session?
       request-login-info

       (nil? requested-response-session-id)
       (if-let
         [logged-in-user-sessions
          (get-user-session-identifiers-for-user
            @auth-data/alloc-auth-logged-in-users request-user-id)]
         (assoc request-login-info
           :alloc-auth/user-session
           (-> logged-in-user-sessions :alloc-auth/user-session))
         request-login-info)

       :else
       (assoc request-login-info
         :alloc-auth/user-session
         requested-response-session-id))]

    (if-let
      [usess-id (get new-login-info :alloc-auth/user-session)]
      (if
        (= usess-id requested-response-session-id)
        (log/info "OK to set response session ID to " usess-id)
        (do
          (log/info "Problem setting response session ID to " requested-response-session-id ", current session set to " usess-id)
          (log/info "Login Info " new-login-info)))
      (do
        (log/info "Problem setting response session ID to " requested-response-session-id
                ". Current user-id is " request-user-id ", current session id is " request-session-id)
        (log/info "Login Info " new-login-info)))
    new-login-info))

(defn
  alloc-auth-attach-response-session-interceptor
  []
  ;; This interceptor is called after the authentication interceptor,
  ;; therefore for an authorized used there should be an :identity
  ;; in the request.
  ;; The interceptor attaches a response channel, specified by the
  ;; 'response-session-id' request header, to the :identity map
  ;; as :alloc-auth/user-session iff there is not one already attached,
  ;; which would be the case if there is an active UI session.
  (interceptor/interceptor
    {:name  ::alloc-auth-attach-response-session
     :enter (fn [ctx]
              (log/info "entering response session assignment interceptor.")
              (log/info "auth identity:" (get-in ctx [:request :identity]))
              (log/info "response session (header):" (gen-utils/possible-string-as-keyword (http/-get-header (:request ctx) "response-session-id")))
              (log/info "logged in users:"
                        (pr-str @auth-data/alloc-auth-logged-in-users))
              (let
                [requested-response-session-id
                 (gen-utils/possible-string-as-keyword
                   (http/-get-header (:request ctx) "response-session-id"))
                 identity-token
                 (cond->
                   (get-in ctx [:request :identity])
                   (some? requested-response-session-id)
                   (check-and-attach-session-id-to-login-info
                     requested-response-session-id))]

                (when-not
                  (:alloc-auth/user-session identity-token)
                  (log/warn "No response session ID available for user."))

                (->
                  ctx
                  (update
                    :response
                    merge
                    {}
                    #_(cookies/cookies-response
                      {:cookies {"session-id"
                                 {:value "999"
                                  :secure true
                                  :same-site :strict}}}))
                  (assoc-in
                    [:request :identity]
                    identity-token))))}))