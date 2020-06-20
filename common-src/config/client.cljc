(ns config.client
  (:require 
   [config.common :as common-config]
   [clojure.string]))

(def ^:const google-client-id common-config/google-client-id)
(def figwheel-ssl-port 9501)

;; Client Only
(def pedestal-port 8080)
(def pedestal-ssl-port 8081)
(def google-callback-url 
  (str "https://" common-config/host 
       (when-not
        (clojure.string/ends-with?
         common-config/host
         "timpsongray.com")
         ":8081") 
       "/auth/google"))
(def google-script-location "https://apis.google.com/js/platform.js")
