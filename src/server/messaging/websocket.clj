(ns server.messaging.websocket
  (:require
    [clojure.string]
    [io.pedestal.http.route :as route]
    [clojure.tools.logging :as log]
    [clojure.core.async :as async]
    [utils.utils :as gen-utils]
    [server.utils
     :refer [value->transit-string
             transit-string->value]]
    [io.pedestal.http.jetty.websockets :as ws]
    [clojure.core.async.impl.protocols :as ap]
    [server.auth.utils :as auth-utils])
  (:import [org.eclipse.jetty.websocket.api Session]
           (java.io ByteArrayOutputStream)))

(defonce ws-clients (atom {}))

(defn get-ws-endpoint-from-session
  [^Session ws-session]
  (-> ws-session
      (.getWebSocketSession)
      (.getRemoteAddress)
      (.toString)))

(defn new-ws-client
  [^Session ws-session send-ch]

  (log/info
    (str "new-ws-client: creating new websocket to client with "
         "uri: " (str (.getRequestURI ^Session ws-session))))
  (let
    [ws-endpoint
     (get-ws-endpoint-from-session ws-session)
     query-string-map
     (some-> ws-session
             (.getRequestURI)
             (.getQuery)
             (route/parse-query-string))
     [session-id ext-token connection-uuid]
     (as->
       query-string-map v
       (mapv #(get v %) [:session-id :ext-token :connection-uuid]))]
    (if (and session-id connection-uuid)
      (let
        [user-id-from-token
         (or
           (auth-utils/get-id-from-ext-token ext-token)
           :anonymous)
         message-text (str "new-ws-client: starting web socket "
                           "with user " (pr-str user-id-from-token) " "
                           "for session-id " session-id " "
                           "with connection-uuid " (pr-str connection-uuid) " "
                           "from " (pr-str ws-endpoint))
         message (value->transit-string
                   {:time (gen-utils/get-local-timestamp-with-offset)
                    :text message-text})]

        (log/info message-text)

        (async/put!
          send-ch
          message)

        (swap! ws-clients
               assoc
               (keyword
                 (name (gen-utils/possible-string-as-keyword user-id-from-token))
                 (str connection-uuid))
               [ws-session send-ch
                (keyword
                  (name (gen-utils/possible-string-as-keyword user-id-from-token))
                  (name (gen-utils/possible-string-as-keyword session-id)))]))
      (log/warn (str "No session-id and connection-uuid "
                     "supplied for websocket creation."
                     "Websocket not created.")))))

(defn- get-ws-conns-for-session-id
  [ws-clients connection-or-session-id]
  (seq
    (keep
      (fn[[ws-conn-key [_ _ combined-session-id]]]
        (if
          (or
            (= ws-conn-key connection-or-session-id)
            (and
              (= connection-or-session-id combined-session-id)
              (= (namespace ws-conn-key) (namespace combined-session-id))))
          ws-conn-key))
      ws-clients)))

(defn disconnect-ws-client
  [user-id session-id]
  (if-let
    [ws-connections
     (get-ws-conns-for-session-id
       @ws-clients
       (keyword
         (name user-id)
         (name session-id)))]
    (doall
      (map
        (fn[lookup-session-id]
          (let
            [[^Session ws-session send-ch combined-session-id]
             (get
               (deref ws-clients)
               lookup-session-id)]
            (if ws-session
              (try
                (do
                  (log/info "Closing web socket for"
                            "user" user-id "with"
                            "session-id" session-id
                            "and connection-uuid" (name lookup-session-id))
                  (async/close! send-ch)
                  (swap! ws-clients dissoc lookup-session-id))
                (catch Exception e
                  (log/warn
                    (str "While disconnecting ws-client, "
                         "couldn't close send channel of ws "
                         (pr-str session-id) ", reason "
                         (pr-str e)))))
              (log/info "Request to close ws channel for"
                        "user" user-id "with"
                        "session-id" session-id
                        "and connection-uuid" (name lookup-session-id)
                        "failed. Session not found."
                        "Available sessions are"
                        (keys (deref ws-clients))))))
        ws-connections))
    (log/info "No web socket sessions found for user"
              user-id "with" session-id
              ". Disconnect isn't possible.")))

(defn clean-up-ws-clients
  []
  (log/debug "Attempting to clean up websocket client(s) in pedestal handler.")
  (doseq [[connection-identifier [^Session ws-session send-ch combined-session-id]] @ws-clients]
    (log/debug
      (str "Inspecting websocket with identifier "
           (pr-str connection-identifier) " - "
           "open? : " (pr-str (.isOpen ws-session))))
    (when-not (.isOpen ws-session)
      (log/debug
        (str "cleaning up websocket client id "
             (pr-str connection-identifier) ", details: "
             (pr-str ws-session)))
      (try
        (async/close! send-ch)
        (catch Exception e
          (log/warn
            (str "couldn't close send channel of websocket "
                 (pr-str ws-session) ", reason "
                 (pr-str e)))))
      (swap! ws-clients dissoc connection-identifier))))

(defn- send-message-to-client
  [connection-identifier message & [message-type]]
  (log/info
    (str "Asked to send web-socket message to individual client "
         (pr-str connection-identifier)
         ", msg is "
         (pr-str message)))
  (if-let
    [ws-connections (get-ws-conns-for-session-id
                      @ws-clients
                      connection-identifier)]
    (doall
      (map
        (fn[connection-key]
          (if-let
            [[ws-session send-ch combined-session-id]
             (get @ws-clients connection-key)]
            (if (or
                  (not (.isOpen ws-session))
                  (ap/closed? send-ch))
              (do
                (log/warn
                  "While trying to send-message-to-client,"
                  "found websocket or websocket channel was closed."
                  "combined-session-id" (pr-str combined-session-id)
                  "connection-key " (pr-str connection-key))
                (clean-up-ws-clients))
              (async/put!
                send-ch
                (value->transit-string
                  {:time (gen-utils/get-local-timestamp-with-offset)
                   :text message
                   :message-type (or message-type :info)})
                (fn[v]
                  (log/debug
                    (str
                      "put! in send-message-to-client "
                      (pr-str connection-key)
                      " returned "
                      (pr-str v))))))
            (log/warn (str "Couldn't find websocket session "
                           "for client connection "
                           (pr-str connection-key)
                           ". Available connections "
                           "are " (pr-str (keys @ws-clients))))))
        ws-connections))
    (log/warn "Couldn't find any web sockets for target-client"
              (:target-client message))))

(defn send-messages-to-clients
  [{message :msg
    target-client :target-client
    message-type :message-type
    :as whole-message}]
  (if (and (some? target-client)
           (not= :none target-client))
    (do
      (log/info
        (str "websocket: asked to send message to clients "
             (pr-str target-client)
             ", msg: "
             (pr-str whole-message)))
      (if (= :all target-client)
        (doseq
          [target-client (keys @ws-clients)]
          (send-message-to-client
            target-client
            message
            message-type))
        (send-message-to-client
          target-client
          message
          message-type)))))

(defn process-incoming-text-message
  [raw-msg]
  (let
    [message (transit-string->value raw-msg)]
    (log/info "Websocket message received "
              (pr-str message))
    (if
      (and
        (map? message)
        (contains? message :asys/ping))
      (let
        [{user-id :asys/ping
          session-id :asys/session-id
          connection-uuid :asys/connection-uuid} message]
        (send-messages-to-clients
          {:msg
           {:asys/pong user-id
            :asys/session-id session-id
            :asys/connection-uuid connection-uuid}
           :target-client
           (keyword
             (name user-id)
             (str connection-uuid))}))
      (log/warn
        (str "Unexpected websocket message received: "
             (pr-str message) ", type: "
             (type message))))))

(defn process-incoming-binary-message
  [payload offset length]
  (log/info
    (str "websocket binary message received, "
         "length = " (str length))))

(defn process-error
  [error]
  (log/error
    (str "websocket error: cause is "
         (:cause (Throwable->map error)))))

(defn process-close
  [num-code reason-text]
  (log/debug
    (str
      "websocket closed event. code: "
      (pr-str num-code) ", reason: "
      (pr-str reason-text)))
  (clean-up-ws-clients))

(def ws-paths
  {"/ws"
   {:on-connect
    (ws/start-ws-connection
      new-ws-client)
    :on-text
    (fn [raw-msg]
      (process-incoming-text-message raw-msg))
    :on-binary
    (fn [payload offset length]
      (process-incoming-binary-message
        payload offset length))
    :on-error
    (fn [error]
      (process-error error))
    :on-close
    (fn [num-code reason-text]
      (process-close
        num-code reason-text))}})

(defn websocket-configurator-for-jetty
  [jetty-servlet-context]
  (ws/add-ws-endpoints
    jetty-servlet-context
    ws-paths))

