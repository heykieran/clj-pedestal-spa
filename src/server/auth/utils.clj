(ns server.auth.utils
  (:require
   [config.server :as server-config]
   [server.auth.data :as auth-data])
  (:import
    [com.google.api.client.googleapis.auth.oauth2
     GoogleIdToken
     GoogleIdToken$Payload
     GoogleIdTokenVerifier
     GoogleIdTokenVerifier$Builder]
    [com.google.api.client.json.jackson2 JacksonFactory]
    [com.google.api.client.http.javanet NetHttpTransport]
    [java.util Collections UUID]))

(defn get-raw-unique-session-id-number
  []
  (swap! auth-data/unique-session-id inc))

(defn get-id-from-email-address
  ([ext-token]
   (get-id-from-email-address ext-token @auth-data/alloc-auth-users))
  ([email-address alloc-auth-users]
   (some
     (fn [[uid {users :users}]]
       (if (contains? users email-address)
         uid))
     alloc-auth-users)))

(defn verify-google-token-response
  [google-client-id token]
  (let
    [verifier
     (-> (GoogleIdTokenVerifier$Builder.
           (NetHttpTransport.)
           (JacksonFactory.))
         (doto
           (.setAudience
             (Collections/singletonList google-client-id)))
         (.build))
     verified-token
     (some->
       verifier
       (.verify token)
       .getPayload
       (select-keys
         ["aud" "email" "email_verified" "iat" "exp" "iss"]))]
    (if (and verified-token
             (= google-client-id (get verified-token "aud"))
             (or (= "https://accounts.google.com" (get verified-token "iss"))
                 (= "accounts.google.com" (get verified-token "iss"))))
      (reduce
        (fn [accum [k v]]
          (assoc
            accum
            (keyword k)
            v))
        {}
        verified-token))))