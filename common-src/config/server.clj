(ns config.server
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [com.walmartlabs.dyn-edn :refer [env-readers]]
    [cheshire.core :as cheshire]
    [config.common :as common-config]))

(def ^:const google-jwt-audience "/projects/486988131533/global/backendServices/8467807766027976055")
(def ^:const google-jwk-url "https://www.gstatic.com/iap/verify/public_key-jwk")
(def ^:const google-client-id common-config/google-client-id)

(def roles-and-users
  {:admin {:roles #{:admin}
           :users #{"admin@timpsongray.com" "heykieran@gmail.com"}}
   :user {:roles #{:user}
          :users #{"user@timpsongray.com"}}})

(def env-config
  (->> "config.edn"
       io/resource
       slurp
       (edn/read-string {:readers (env-readers)})))

(def local-user-timeout-minutes 15)

(def host common-config/host)
(def my-hostname (str "https://" host))

(def keystore-location (get-in env-config [:jetty :keystore-location]))
(def keystore-password (get-in env-config [:jetty :keystore-password]))
(def server-ssl-port (get-in env-config [:jetty :ssl-port]))
(def server-port (get-in env-config [:jetty :port]))
(def app-build-id (get-in env-config [:build-info :id]))
(def session-store-key (get-in env-config [:auth :session-store-key]))

;; The contents of the env variable can be generated
;; using
;;  (cheshire/generate-string
;;    (debug-sign/generate-ec256-debug-keypair-as-jwk)))
;; BE CAREFUL BECAUSE THE REPL WILL ADD BACKSLASHES

(def debug-local-jwt
  (cheshire/parse-string
    (get-in env-config [:auth :debug-jwt-key])
    true))