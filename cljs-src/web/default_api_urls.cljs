(ns web.default-api-urls
  (:require
    [oops.core
     :refer [oget oset! ocall oapply ocall! oapply!
             oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
    [config.client :as config]
    [clojure.string :as str]))

(js/console.log
  (str "Request Location Info:"
       "\nProtocol: " (oget js/location :protocol)
       "\nHost: " (oget js/location :hostname)
       "\nPort: " (if
                    (= "" (oget js/location :port))
                    "80")
       "\nCallback: " config/google-callback-url))

(def server-port
  (if (str/ends-with? 
       (oget js/location :hostname)
       "timpsongray.com")
    "80"
    (if (= "https:" (oget js/location :protocol)) 
      config/pedestal-ssl-port 
      config/pedestal-port)))

(def hostname-port
  (str
   (oget js/location :hostname)
   (when
    (not= "80" server-port)
     (str ":" server-port))))

(def
  base-api-server-url
  (let
    [protocol (oget js/location :protocol)]
    (str protocol "//" hostname-port "/api")))

(def
  base-auth-server-url
  (let
    [protocol (oget js/location :protocol)]
    (str protocol "//" hostname-port "/auth")))

(def
  base-websocket-url
  (let
    [protocol (oget js/location :protocol)
     ws-protocol (if (= "https:" protocol) "wss:" "ws:") ]
    (str ws-protocol "//" hostname-port "/ws")))

(def
  base-sse-url
  (let
    [protocol (oget js/location :protocol)]
    (str protocol "//" hostname-port "/sse")))

(def
  base-content-url
  (let
    [protocol (oget js/location :protocol)]
    (str protocol "//" hostname-port "/httpdocs/content")))

(defn generate-content-url
  [content-path]
  (str base-content-url "/" content-path))

(def current-user-auth-url (str base-auth-server-url "/isauthenticated"))
(def set-current-user-auth-url (str base-auth-server-url "/setid"))
(def log-out-user-auth-url (str base-auth-server-url "/logout"))
(def test-api-url (str base-api-server-url "/test"))
(def get-ws-auth-url (str base-auth-server-url "/wsauth"))

(def get-secured-resource-test-url
  (str base-api-server-url "/getsecresource"))


