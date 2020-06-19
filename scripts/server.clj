(require
  '[server.fe-handler :as fe]
  '[ring.adapter.jetty :refer [run-jetty]]
  '[config.client :as client-config]
  '[config.server :as server-config])

(run-jetty
  fe/wrapped-app
  {:ssl? true
   :ssl-port client-config/figwheel-ssl-port
   :keystore server-config/keystore-location
   :key-password server-config/keystore-password
   :http? false
   :join? false})


