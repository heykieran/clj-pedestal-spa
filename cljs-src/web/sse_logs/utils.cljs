(ns web.sse-logs.utils
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:require
    [re-frame.core :as rf]
    [cljs.core.async :as a :refer [<! >!]]
    [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                       oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
    [cognitect.transit :as transit]
    [web.sse-logs.es]
    [web.default-api-urls :as api-urls]
    [cognitect.transit :as t]))

(defn add-internal-message-string-to-log
  [message-string & [message-type]]
  (let
    [message
     {:time "Unknown"
      :text message-string}]
    (rf/dispatch
      [:sse-logs/add-message-to-log
       :client
       message
       (or message-type :info)])))

(defn add-message-to-log
  [raw-message]
  (rf/dispatch
    [:sse-logs/add-message-to-log
     :server raw-message
     (or (:message-type raw-message) :info)]))

(defn sse-event-source-listener
  [ev]
  (js/console.log
    (str "SSE listener received, "
         (pr-str ev)))
  (js/console.log ev)
  (add-message-to-log
    (t/read (t/reader :json) (oget ev :data))))

(defn build-event-stream-url
  [user-id event-stream-id connection-uuid]
  (str api-urls/base-sse-url "/events/"
       (name user-id)
       "/"
       event-stream-id
       "/"
       connection-uuid))

(defn connect-sse-for-log
  [user-id session-id connection-uuid ext-token log-atom]
  (println "connecting sse log stream for user: "
           (pr-str user-id) ", session: "
           (pr-str session-id) ", connection: "
           (pr-str connection-uuid)
           ".")

  (let [{existing-sse-endpoint :sse-endpoint
         existing-client-id :session-id}
        @log-atom]
    (when existing-sse-endpoint
      (println "SSE stream already exists.")))
  (let
    [event-stream-id (name session-id)
     event-stream-url (build-event-stream-url
                        user-id event-stream-id connection-uuid)
     sse-endpoint
     (new js/EventSource
          event-stream-url
          (clj->js
            {:withCredentials true}))]
    (js/console.log (str "Created SSE to "
                         event-stream-url ", EventSource = "
                         (pr-str sse-endpoint)))
    (.addEventListener
        sse-endpoint
        "log-msg"
        sse-event-source-listener)

    (set!
      (.-onerror sse-endpoint)
      (fn [error]
        (js/console.log "SSE Error #1")
        (js/console.log error)))

    (swap!
      log-atom
      (fn[o n]
        (js/console.log
          "In swap for SSE log atom. current value: "
          (pr-str o)
          ", new value: "
          (pr-str n))
        n)
      {:session-id session-id
       :sse-endpoint sse-endpoint})))

(defn disconnect-web-socket-for-log
  [log-atom]
  (let
    [{sse-endpoint :sse-endpoint
      session-id :session-id} @log-atom]
    (if session-id
      (do
        (println
          (str "Disconnecting sse endpoint associated with session "
               (pr-str session-id)))
        (.removeEventListener
          sse-endpoint
          "log-msg"
          sse-event-source-listener)
        (.close sse-endpoint))
      (println "No session id available. Declined to issue close()."))))


