(ns server.auth.base
  (:require
    [io.pedestal.interceptor :as interceptor]
    [io.pedestal.interceptor.chain :as interceptor.chain]
    [io.pedestal.interceptor.error :refer [error-dispatch]]
    [ring.util.response :as ring-response]
    [buddy.auth :as auth]
    [buddy.auth.backends :as auth.backends]
    [buddy.auth.middleware :as auth.middleware]
    [buddy.auth.accessrules :as auth.accessrules]
    [hiccup.core :refer [html]]
    [clojure.tools.logging :as log]
    [clojure.pprint]
    [java-time :as t]
    [config.server :as server-config]
    [server.auth.data :as auth-data]
    [server.auth.utils :as auth-utils]))

(def debug-secret "secret")

(defn html-response-plain [body]
  {:status  200
   :body    body
   :headers {"Content-Type" "text/html"}})

(defn raw-response [body]
  {:status 200
   :body   body})

(defn extract-user-response-from-token
  [id-token]
  (let
    [{user              :alloc-auth/user-id
      authority         :alloc-auth/token-type
      client-id         :alloc-auth/user-session
      ext-token         :alloc-auth/ext-token
      {expiration :exp} :alloc-auth/token}
     id-token]
    {:user      user
     :authority authority
     :client-id client-id :expires expiration}))

(defn get-current-logged-in-user
  [request]
  (let [session (get request :session)
        session-id (get request :asys/session-id)]
    (raw-response
      (if (auth/authenticated? request)
        (let
          [identity-token (get-in request [:identity])
           {user              :alloc-auth/user-id
            authority         :alloc-auth/token-type
            client-id         :alloc-auth/user-session
            ext-token         :alloc-auth/ext-token
            {expiration :exp} :alloc-auth/token}
           identity-token]

          (-> identity-token
              (extract-user-response-from-token)))
        {:user      nil :authority nil :expires nil
         :client-id nil}))))

(defn attach-session-id-to-login-info
  [{user-id :alloc-auth/user-id :as login-info}]
  (if user-id
    (assoc login-info
      :alloc-auth/user-session
      (keyword
        (str (if (keyword? user-id)
               (name user-id)
               user-id)
             "-"
             (auth-utils/get-raw-unique-session-id-number))))))

(defn alloc-auth-google-login
  [request]
  (let
    [{google-token :idtoken}
     (get request :form-params)
     verified-token
     (auth-utils/verify-google-token-response server-config/google-client-id google-token)]

    (if verified-token
      (let
        [user-email (:email verified-token)
         user-id (auth-utils/get-id-from-email-address
                   user-email
                   @auth-data/alloc-auth-users)
         identity-token
         (->
           {:alloc-auth/user-id    user-id
            :alloc-auth/token-type :google
            :alloc-auth/token      verified-token}
           (attach-session-id-to-login-info))
         session (:session request)]
        (log/info
          "In alloc-auth-google-login. "
          "Setting identity in"
          "existing session:"
          (pr-str session))
        (swap! auth-data/alloc-auth-logged-in-users
               assoc
               [(:alloc-auth/user-id identity-token)
                (:alloc-auth/user-session identity-token)
                "single-session-only"]
               identity-token)
        (->
          (raw-response
            (-> identity-token
                (extract-user-response-from-token)))
          (assoc-in [:session :identity] identity-token)))
      (ring-response/bad-request
        {:message (str "Token could not be verified.")}))))

(defn alloc-auth-local-login
  [user-email]
  (let
    [user-id (auth-utils/get-id-from-email-address
               user-email @auth-data/alloc-auth-users)
     issue-date (System/currentTimeMillis)
     expiration-date (+ issue-date (* 1000 60 server-config/local-user-timeout-minutes))]
    (log/info
      (str "Setting token for local user " (pr-str user-id)))
    (if user-id
      (->
        {:alloc-auth/user-id    user-id
         :alloc-auth/token-type :local
         :alloc-auth/token      {:email user-email
                                 :iss   "local"
                                 :aud   "local"
                                 :iat   (int (/ issue-date 1000))
                                 :exp   (int (/ expiration-date 1000))}}
        (attach-session-id-to-login-info)))))

(defn build-permissions
  [routes]
  (reset!
    auth-data/alloc-auth-permissions
    (reduce
      (fn [a {route-name :route-name :as path-detail}]
        (let
          [allow-user?
           (and (keyword? route-name) (= "alloc-user" (namespace route-name)))
           allow-none?
           (and (keyword? route-name) (= "alloc-public" (namespace route-name)))]
          (-> a
              (assoc
                route-name
                {:path-detail
                 (select-keys
                   path-detail
                   [:route-name :path :method
                    :path-parts :path-re])
                 :permissions
                 {:roles
                  (if allow-none?
                    #{:public}
                    (if allow-user?
                      #{:admin :user}
                      #{:admin}))}}))))
      {}
      routes)))

(defn build-users-table
  [roles-and-users]
  (reset!
    auth-data/alloc-auth-users
    roles-and-users))

(defn alloc-auth-logout-current-user
  [{session-user    :alloc-auth/user-id
    session-id      :alloc-auth/user-session
    connection-uuid :alloc-auth/connection-uuid}]
  (if session-user
    (do
      (log/info
        "Logging out current user"
        (pr-str session-user)
        "with session"
        (pr-str session-id)
        "and connection-uuid"
        (pr-str connection-uuid))
      (do
        (swap! auth-data/alloc-auth-logged-in-users
               dissoc
               [session-user session-id "single-session-only"])
        {:user nil :authority nil :expires nil :message "User logged out"}))
    (raw-response
      {:user    nil :authority nil :expires nil :message "User not logged in."})))

(defn disconnect-session
  [request]
  (let
    [{{user-id              :alloc-auth/user-id
       user-session-id      :alloc-auth/user-session
       user-connection-uuid :alloc-auth/connection-uuid
       :as                  request-identity}
      :identity} request]
    (->
      (alloc-auth-logout-current-user request-identity)
      (raw-response)
      (assoc :session {}))))

(defn expire-identity-interceptor
  []
  (letfn
    [(->string-from-ticks [ticks]
       (t/format
         (t/local-date-time
           (t/instant (* ticks 1000))
           (t/zone-id))))
     (expired-token? [expiration now]
       (not (and expiration
                 (> expiration now))))]
    (interceptor/interceptor
      {:name  ::alloc-auth-identity-expire
       :leave (fn [{{expired-date     :expired-date
                     expired-identity :expired-identity}
                    :alloc-auth/identity-expired :as ctx}]
                (if expired-date
                  (do
                    (log/warn
                      (str "identity expired. Setting "
                           "session to {}."))
                    (assoc
                      ctx
                      :response
                      (->
                        (alloc-auth-logout-current-user expired-identity)
                        (assoc :reason "expired")
                        (raw-response)
                        (ring-response/status 401)
                        (assoc :session {}))))
                  ctx))

       :enter (fn [ctx]
                (let [now (int (/ (System/currentTimeMillis) 1000))
                      identity (get-in ctx [:request :session :identity])]
                  (if (and identity (not-empty identity))
                    (let [{auth-token :alloc-auth/token} identity
                          {expiration :exp} auth-token]
                      (log/info
                        (str "Token expiration: "
                             (->string-from-ticks expiration)
                             ", now: "
                             (->string-from-ticks now)
                             ", expired: "
                             (pr-str (expired-token? expiration now))))
                      (if (not (expired-token? expiration now))
                        ctx
                        ;; expired identity
                        ;; terminate processing with flag
                        (interceptor.chain/terminate
                          (assoc
                            ctx
                            :alloc-auth/identity-expired
                            {:expired-date  now
                             :expired-identity identity}))))
                    ctx)))})))

(def alloc-auth-session-auth-backend
  (auth.backends/session
   {:unauthorized-handler
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
            (str "NOT AUTHORIZED (SESSION): In unauthenticated handler for "
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
              (str "Session authenticated, but not authorized for access to .\n"
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

(defn alloc-auth-authentication-interceptor
  [backend]
  (interceptor/interceptor
    {:name  ::alloc-auth-authenticate
     :enter (fn [ctx]
              (-> ctx
                  (assoc
                    :auth/backend
                    backend)
                  (update
                    :request
                    auth.middleware/authentication-request
                    backend)))}))

(defn alloc-auth-get-roles-for-identity
  [identity-name]
  (if (nil? identity-name)
    #{}
    (get-in
      @auth-data/alloc-auth-users
      [identity-name :roles]
      #{})))

(defn alloc-auth-user-roles-interceptor
  []
  {:name  ::alloc-auth-user-roles
   :enter (fn [ctx]
            (log/info "Assigning roles for identity " (pr-str (get-in ctx [:request :identity])))
            (let
              [{identity-user-id    :alloc-auth/user-id
                identity-token-type :alloc-auth/token-type
                identity-token      :alloc-auth/token
                identity-ext-token  :alloc-auth/ext-token}
               (get-in ctx [:request :identity])]
              (assoc
                ctx
                :alloc-auth/auth
                {:user  identity-user-id
                 :roles (alloc-auth-get-roles-for-identity identity-user-id)})))})

(defn alloc-auth-unauthorized-interceptor
  []
  (letfn
    [(unauthorized-fn [ctx ex]
       (if-let
         [handling-backend (:auth/backend ctx)]
         (assoc
           ctx
           :response
           (.-handle-unauthorized
             handling-backend
             (:request ctx)
             {:details
              {:request (ex-data (ex-cause ex))
               :message (pr-str (ex-message (ex-cause ex)))}}))
         (do
           (log/error "Unauthorized requests, but there is no backend installed to handle the exception.")
           (throw "No auth backend found."))))]
    (error-dispatch
      [ctx ex]
      [{:exception-type :clojure.lang.ExceptionInfo :interceptor ::alloc-auth-permission-checker}]
      (try
        (unauthorized-fn ctx ex)
        (catch Exception e
          (assoc ctx ::interceptor.chain/error e)))
      [{:exception-type :clojure.lang.ExceptionInfo :interceptor :alloc-auth-access-rule-checker}]
      (try
        (unauthorized-fn ctx ex)
        (catch Exception e
          (assoc ctx ::interceptor.chain/error e)))
      :else
      (assoc ctx ::interceptor.chain/error ex))))

(defn alloc-auth-client-session-id-tagger-interceptor-factory
  []
  (interceptor/interceptor
    {:name  ::alloc-auth-session-id-tagger
     :enter (fn [ctx]
              (let
                [{{req-session  :session
                   req-identity :identity
                   :as          req}
                  :request} ctx]
                (log/info
                  "Tagging request with session"
                  req-session
                  "auth:" (pr-str (auth/authenticated? req))
                  "current:" (pr-str
                               req-identity
                               #_(select-keys
                                   req-identity
                                   [:alloc-auth/user-session
                                    :alloc-auth/user-id])))
                (if (auth/authenticated? req)
                  (let
                    [{session-id :alloc-auth/user-session
                      user-id    :alloc-auth/user-id}
                     req-identity
                     session-id-tag
                     (when
                       (and user-id session-id)
                       (keyword (name user-id)
                                (name session-id)))]
                    (log/info
                      (str "Tagging request for uri "
                           (pr-str (:uri req)) " from "
                           (pr-str (:remote-addr req))
                           " for user " (pr-str user-id)
                           " with " (pr-str session-id-tag)))
                    (update-in
                      ctx
                      [:request]
                      merge
                      (when session-id-tag
                        {:asys/session-id
                         session-id-tag})))
                  ctx)))}))

(defn alloc-auth-permission-checker-interceptor-factory
  []
  (interceptor/interceptor
    {:name  ::alloc-auth-permission-checker
     :enter (fn [ctx]
              (log/info
                (str "Checking Identity: "
                     (pr-str (get-in ctx [:request :identity :alloc-auth/user-id] :unauthenticated))
                     " for access to "
                     (pr-str (get-in ctx [:request :path-info]))
                     " with path params "
                     (pr-str (get-in ctx [:request :path-params]))
                     " for route name "
                     (pr-str (get-in ctx [:route :route-name]))
                     " with session "
                     (pr-str (get-in ctx [:request :session]))))
              (let
                [{req-path                                       :path-info
                  res-path-params                                :path-params
                  {identity-user-id    :alloc-auth/user-id
                   identity-token-type :alloc-auth/token-type
                   identity-token      :alloc-auth/token
                   user-session        :alloc-auth/user-session} :identity}
                 (get-in ctx [:request])
                 {route-name        :route-name route-method :method
                  route-path-re     :path-re route-path-parts :path-parts
                  route-path-params :path-params}
                 (get-in ctx [:route])
                 {user :user roles :roles}
                 (get-in ctx [:alloc-auth/auth])
                 required-roles
                 (get-in
                   @auth-data/alloc-auth-permissions
                   [route-name :permissions :roles])]

                (log/info
                  (str "User Roles: "
                       (pr-str roles)
                       " , required roles "
                       (pr-str required-roles)))

                (if (and
                      (not (contains? required-roles :public))
                      (empty? (clojure.set/intersection
                                roles required-roles)))
                  (throw
                    (ex-info "Alloc-Unauthorized"
                             {:path         req-path
                              :path-params  res-path-params
                              :user         user
                              :roles        roles
                              :identity     identity
                              :required     required-roles
                              :user-session user-session}))
                  (update-in
                    ctx
                    [:request]
                    assoc :auth-alloc "ok"))))}))

(defn alloc-auth-rules-checker-interceptor-factory
  [rules]
  (interceptor/interceptor
    {:name  :alloc-auth-access-rule-checker
     :enter (fn [context]
              (let
                [request (:request context)
                 policy :allow
                 w-a-rules-fn
                 (auth.accessrules/wrap-access-rules
                   (fn [req] :ok)
                   {:rules rules :policy policy})]
                (w-a-rules-fn request)
                context))}))

(defn alloc-auth-logout-any-user
  [request]
  (let
    [{requested-sign-out-user       :user-id
      requested-sign-out-session-id :session-id}
     (get request :transit-params)
     session-user (get-in request [:identity :alloc-auth/user-id])
     matching-logged-in-user
     (get @auth-data/alloc-auth-logged-in-users
          [requested-sign-out-user requested-sign-out-session-id "single-session-only"])]
    (if (and session-user matching-logged-in-user)
      (do
        (log/info
          "Logging out"
          (pr-str requested-sign-out-user)
          "with session"
          (pr-str requested-sign-out-session-id)
          ", requested by "
          (pr-str session-user) ", matching "
          (pr-str matching-logged-in-user)
          "Setting session to {} in alloc-auth-logout-post.")
        (do
          (swap! auth-data/alloc-auth-logged-in-users
                 dissoc
                 [requested-sign-out-user requested-sign-out-session-id "single-session-only"])
          (->
            (raw-response {:result :user-logged-out :message "User logged out."})
            (assoc :session {}))))
      (raw-response {:result :nothing-to-do :message "User not logged in."}))))

(defn alloc-auth-explicitly-set-identity-of-user-post
  [request]
  (log/info
    "Asked to explicitly set user identity. "
    "session:"
    (get-in request [:session]))
  (let
    [{user-email :email}
     (get request :transit-params)
     local-token (some->
                   user-email
                   (alloc-auth-local-login))]

    (if local-token
      (do
        (log/info
          (str
            "Explicitly setting identity for "
            "session to " (pr-str local-token)))

        (swap! auth-data/alloc-auth-logged-in-users
               assoc
               [(:alloc-auth/user-id local-token)
                (:alloc-auth/user-session local-token)
                "single-session-only"]
               local-token)

        (->
          (raw-response
            (-> local-token
                (extract-user-response-from-token)))
          (assoc-in [:session :identity] local-token)))

      (ring-response/bad-request
        {:message (str "Unknown user " user-email)}))))


