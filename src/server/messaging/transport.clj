(ns server.messaging.transport
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [server.messaging.channel :as rlog]
            [server.messaging.websocket :as server-ws]
            [server.messaging.sse :as server-sse]))

(defmulti send-messages-to-clients
          (fn[t-type _] t-type))

(defmethod send-messages-to-clients
  :web-socket
  [_ log-msg]
  (server-ws/send-messages-to-clients log-msg))

(defmethod send-messages-to-clients
  :sse
  [_ log-msg]
  (server-sse/send-messages-to-clients log-msg))

(defn start-processing-sub
  [transport-type message-channel-atom]
  ;; If the channel already exists, then close it.
  (when-let [ch (deref message-channel-atom)]
    (async/close! ch))
  ;; subscribe a channel to be contained in the
  ;; server-messages-channel-destined-for-all-clients atom
  ;; to the server-messages-publication with the topic of
  ;; :log-msg i.e. anything in the publication with key of
  ;; :topic and a value of :log-msg
  (async/sub
    rlog/server-messages-publication
    :log-msg
    (reset! message-channel-atom (async/chan)))
  ;; start a go-loop that takes messages off the channel in the
  ;; atom and pass it to the send-messages-to-clients function
  (log/info "Starting " (pr-str transport-type) " log message loop")
  (async/go
    (loop []
      (when-let
        [log-msg (async/<! (deref message-channel-atom))]
        (send-messages-to-clients transport-type log-msg)
        (recur)))))

(defn message-transport
  [transport-type message-channel-atom]
  (start-processing-sub transport-type message-channel-atom))
