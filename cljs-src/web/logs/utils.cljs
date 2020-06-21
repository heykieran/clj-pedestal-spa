(ns web.logs.utils
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:require
    [re-frame.core :as rf]
    [cljs.core.async :as a :refer [<! >!]]
    [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                       oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
    [web.logs.es]
    [web.default-api-urls :as api-urls]
    [haslett.client :as ws]
    [haslett.format :as fmt]))

(defn add-internal-message-string-to-log
  [message-string & [message-type]]
  (let
    [message
     {:time
      "Unknown"
      :text
      message-string}]
    (rf/dispatch
      [:logs/add-message-to-log
       :client
       message
       (or message-type :info)])))

(defn add-message-to-log
  [raw-message]
  (println (str "Raw message "
                (pr-str raw-message)))
  (rf/dispatch
    [:logs/add-message-to-log
     :server raw-message
     (or (:message-type raw-message) :info)]))

(declare connect-ws-for-log)

(defn decide-and-restart
  [msg user-id session-id connection-uuid ext-token log-atom]
  (add-internal-message-string-to-log
    (str "Received close event code: " (:code msg)
         " with message " (pr-str msg) ))
  (let
    [ws-close-code (:code msg)]
    (case ws-close-code
      (1001 1006 10000)
      (do
        (js/console.log
          (str
            "Will attempt reconnection of web socket "
            "for user " (pr-str user-id) " "
            "with session-id "(pr-str session-id) " and "
            "connection-uuid " (pr-str connection-uuid)))
        (if
          (and
            @log-atom
            (:session-id @log-atom)
            (:connection-uuid @log-atom))
          (connect-ws-for-log
            user-id
            session-id
            connection-uuid
            ext-token
            log-atom)
          (js/console.log "No session-id and connection-uuid. Declined to restart")))
      (do
        (js/console.log
          (str "Close code doesn't match restart codes, "
               "won't reconnect explicitly. :ping may do so."))))))

(defn connect-ws-for-log
  [user-id session-id connection-uuid ext-token log-atom]
  (println "connecting logging websocket for "
           "user " (pr-str user-id) ", with client-id "
           (pr-str session-id) "and connection-uuid "
           (pr-str connection-uuid))
  (let [{existing-websocket :web-socket
         existing-user-id :user-id
         existing-session-id :session-id
         existing-connection-uuid :connection-uuid}
        @log-atom]
    (when existing-websocket
      (if (ws/connected? existing-websocket)
        (do
          (println
            (str "Web Socket is currently connected. "
                 "Closing existing connected websocket for "
                 (keyword (name existing-user-id)
                          (name existing-session-id))
                 " with connection-uuid "
                 (pr-str existing-connection-uuid)))
          (ws/close existing-websocket))
        (println
          (str "Existing websocket to "
               (keyword (name existing-user-id)
                        (name existing-session-id)) " "
               "exists, but it is not connected.")))))
  (go
    (let
      [web-socket
       (<! (ws/connect
             (str
               api-urls/base-websocket-url
               "?session-id=" session-id
               "&ext-token=" ext-token
               "&connection-uuid=" connection-uuid)
             {:format fmt/transit}))]
      (swap!
        log-atom
        (fn[o n]
          (js/console.log
            "Resetting log details to "
            (pr-str (select-keys n [:user-id :session-id :connection-uuid]))
            " from "
            (pr-str (select-keys o [:user-id :session-id :connection-uuid])))
          n)
        {:user-id user-id
         :session-id session-id
         :connection-uuid connection-uuid
         :web-socket web-socket
         :ext-token ext-token})
      (go
        (loop []
          (when-let
            [msg (<! (:source web-socket))]
            (add-message-to-log msg)
            (recur))))
      (when-let
        [msg (<! (:close-status web-socket))]
        (js/console.log
          "close-status message received -> " (pr-str msg))
        (decide-and-restart
          msg
          user-id
          session-id
          connection-uuid
          ext-token
          log-atom)))))

(defn disconnect-web-socket-for-log
  [log-atom]
  (let
    [{web-socket :web-socket
      user-id :user-id
      session-id :session-id
      connection-uuid :connection-uuid} @log-atom]
    (if (and session-id connection-uuid)
      (do
        (println
          (str "Disconnecting web socket associated with "
               (pr-str user-id) "/" (pr-str session-id)
               " with connection uuid "
               connection-uuid))
        (if (ws/connected? web-socket)
          (ws/close web-socket)
          (println "Unable to close web socket. It's not connected.")))
      (println "No session id available. Declined to issue close()."))))

(defn send-ping[log-atom]
  (let
    [{web-socket :web-socket
      user-id :user-id
      session-id :session-id
      ext-token :ext-token
      connection-uuid :connection-uuid} @log-atom]
    (if session-id
      (do
        (println
          (str "Sending Ping for "
               (pr-str user-id) "/"
               (pr-str session-id)
               " at " (pr-str connection-uuid)))
        (if (ws/connected? web-socket)
          (go
            (>! (:sink web-socket)
                {:asys/ping user-id
                 :asys/session-id session-id
                 :asys/connection-uuid connection-uuid}))
          (do
            (println
              (str "PING: No websocket connected. "
                   "Attempting reconnect for "
                   (pr-str user-id) "/"
                   (pr-str session-id) ", "
                   "at " (pr-str connection-uuid)))
            (connect-ws-for-log
              user-id
              session-id
              connection-uuid
              ext-token log-atom))))
      (println "No session id available. No ping sent."))))

