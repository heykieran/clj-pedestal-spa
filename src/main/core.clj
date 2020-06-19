(ns main.core
  (:require
    [clojure.tools.logging :as log]
    [server.be-handler-pdstl :refer [start]]
    [java-time :as t])
  (:gen-class))

(defn -main[]
  (start))


