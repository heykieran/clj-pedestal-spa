(ns server.messaging.sse
  (:require
    [clojure.string]
    [clojure.core.async :as async]
    [io.pedestal.http.route :as route]
    [clojure.tools.logging :as log]
    [clojure.core.async :as async]
    [utils.utils :as gen-utils]
    [server.utils
     :refer [value->transit-string
             transit-string->value
             value->json-string]]
    [io.pedestal.http.jetty.websockets :as ws]
    [io.pedestal.http.sse :as sse]
    [clojure.core.async.impl.protocols :as ap]
    [java-time :as time])
  (:import [org.eclipse.jetty.websocket.api Session]
           (java.io ByteArrayOutputStream)))

(defonce sse-clients (atom {}))

(defn sse-stream-ready
  [event-chan context]
  (let
    [{{uri :uri
       {user :user session-id :session-id
        connection-uuid :connection-uuid} :path-params}
      :request}
     context]

    (log/info "Starting event stream for user"
              (pr-str user) "with session-id"
              (pr-str session-id) ". Channel:"
              (pr-str (hash event-chan)))
    (swap!
      sse-clients
      assoc
      (keyword
        (name (gen-utils/possible-string-as-keyword user))
        (str connection-uuid))
      {:event-channel event-chan :uri uri
       :session-id (keyword
                     (name user)
                     (name session-id))})

    (async/>!!
      event-chan
      {:name "log-msg"
       :data (value->json-string
               {:time (gen-utils/get-local-timestamp-with-offset)
                :text (str "Starting SSE stream for user "
                           (gen-utils/possible-keyword-as-string user)
                           " with session-id "
                           (gen-utils/possible-keyword-as-string session-id))})})))

(defn- get-sse-conns-for-session-id
  [sse-clients connection-or-session-id]
  (seq
    (keep
      (fn[[sse-conn-key
           {sse-channel :event-channel
            uri :uri session-id :session-id}]]
        (if
          (or
            (= sse-conn-key connection-or-session-id)
            (and
              (= connection-or-session-id session-id)
              (= (namespace sse-conn-key) (namespace session-id))))
          sse-conn-key))
      sse-clients)))

(defn disconnect-sse-client
  [user-id session-id]
  (if-let
    [sse-connections
     (get-sse-conns-for-session-id
       @sse-clients
       (keyword
         (name user-id)
         (name session-id)))]
    (doall
      (map
        (fn[lookup-session-id]
          (let
            [{sse-channel :event-channel uri :uri i-session-id :session-id}
             (get
               (deref sse-clients)
               lookup-session-id)]
            (if sse-channel
              (try
                (do
                  (async/close! sse-channel)
                  (swap! sse-clients dissoc lookup-session-id))
                (catch Exception e
                  (log/warn
                    (str "While disconnecting sse-client, "
                         "couldn't close event channel of sse "
                         (pr-str lookup-session-id) ", reason "
                         (pr-str e)))))
              (log/info "Request to close sse channel for"
                        "user" user-id "with"
                        "session-id" session-id
                        "failed. Session not found."
                        "Available sessions are"
                        (keys (deref sse-clients))))))
        sse-connections))
    (log/info "No SSE sessions found for user"
              user-id "with" session-id
              ". Disconnect isn't possible.")))

(defn clean-up-sse-clients
  []
  (log/debug "Attempting to clean up sse client(s) in pedestal handler.")
  (doseq [[session-id
           {sse-channel :event-channel
            uri :uri i-session-id :session-id}]
          @sse-clients]
    (log/debug
      (str "Inspecting sse endpoint with session-id "
           (pr-str session-id) " - "
           "closed? : "
           (pr-str
             (clojure.core.async.impl.protocols/closed? sse-channel))))
    (when (clojure.core.async.impl.protocols/closed? sse-channel)
      (log/debug
        (str "cleaning up sse client id "
             (pr-str session-id)))
      (try
        (async/close! sse-channel)
        (catch Exception e
          (log/warn
            (str "couldn't close event channel of sse "
                 (pr-str session-id) ", reason "
                 (pr-str e)))))
      (swap! sse-clients dissoc session-id))))

(defn- send-message-to-client
  [session-id message & [message-type]]
  (log/info
    (str "asked to send SSE message to individual client id "
         (pr-str session-id)
         ", msg is "
         (pr-str message)))
  (if-let
    [sse-connections
     (get-sse-conns-for-session-id
                      @sse-clients
                      session-id)]
    (doall
      (map
        (fn[connection-key]
          (if-let
            [{sse-channel :event-channel uri :uri i-session-id :session-id}
             (get @sse-clients
                  connection-key)]
            (if-not
              (async/put!
                sse-channel
                {:name   "log-msg"
                 :data   (value->json-string
                           {:time (gen-utils/get-local-timestamp-with-offset)
                            :text message
                            :message-type (or message-type :info)})}
                (fn[v]
                  (log/debug
                    (str
                      "put! in sse send-message-to-client id "
                      (pr-str session-id)
                      " returned "
                      (pr-str v)))))
              (do
                (log/warn
                  (str "while trying send-message-to-client, "
                       "found sse channel was closed. "
                       (pr-str sse-channel)))
                (clean-up-sse-clients)))
            (log/warn (str "Couldn't find sse session "
                           "for client id "
                           (pr-str session-id) ". Available sessions "
                           "are " (pr-str (keys @sse-clients))))))
        sse-connections))
    (log/warn "Couldn't find any SSE connections for target-client"
              session-id)))

(defn send-messages-to-clients
  [{message :msg
    target-client :target-client
    message-type :message-type
    :as whole-message}]
  (if (and (some? target-client)
           (not= :none target-client))
    (do
      (log/debug
        (str "SSE: asked to send message to clients "
             (pr-str target-client)
             ", msg: "
             (pr-str whole-message)))
      (if (= :all target-client)
        (doseq
          [target-client (keys @sse-clients)]
          (send-message-to-client
            target-client
            message
            message-type))
        (send-message-to-client
          target-client
          message
          message-type)))))

(defn start-sse-event-stream
  [context]
  (let
    [{{uri :uri {user :user session-id :session-id connection-uuid :connection-uuid} :path-params} :request} context
     sse-channel-key (keyword
                       (name (gen-utils/possible-string-as-keyword user))
                       (str connection-uuid))]
    (log/info "Asked to start SSE event stream for " sse-channel-key)
    (if (get @sse-clients sse-channel-key)
      (do
        (log/info "SSE event stream already exists for" sse-channel-key)
        context)
      (sse/start-stream
        sse-stream-ready
        context
        10 10
        {:on-client-disconnect
         (fn[ctx]
           (let [uri (-> ctx :request :uri)]
             (log/info "SSE client disconnection event. uri = " (str uri))
             (if-let
               [disconnecting-session-id
                (some
                  (fn[[session-id {sse-channel :event-channel uri-orig :uri}]]
                    (if (= uri uri-orig)
                      session-id))
                  @sse-clients)]
               (do
                 (log/info "Removing SSE client session from registry - " disconnecting-session-id)
                 (swap! sse-clients dissoc disconnecting-session-id))
               (log/warn
                 "No record of SSE client connected to" (str uri)
                 "It may have been removed during logoff."))))}))))

(def sse-create-event-stream-interceptor
  {:name ::sse-create-event-stream-interceptor
   :enter (fn [context]
            (start-sse-event-stream context))})
