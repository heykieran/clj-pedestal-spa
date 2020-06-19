(ns control
  (:require
    [server.be-handler-pdstl
     :refer [start start-dev]]))

(defn shutdown-hook[]
  (println "Shutting down agents...")
  (shutdown-agents))

(defn -main[& args]
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread. ^Runnable shutdown-hook))
  (start))

(comment
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread. ^Runnable shutdown-hook))
  (start-dev))

