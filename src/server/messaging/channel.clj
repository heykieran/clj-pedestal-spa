(ns server.messaging.channel
  (:require
    [clojure.core.async :refer [>! >!! go chan pub]]
    [clojure.tools.logging :as log]))

(defonce
  server-messages-channel-destined-for-all-clients
  (atom nil))

;; Used only in this name space. Used as the basis
;; for a publication based on :topic
(defonce
  server-messages-channel
  (chan 100))

(defonce
  server-messages-publication
  (pub server-messages-channel #(:topic %)))

(defn write-message-to-server-message-channel
  "This function takes a message (msg) and a client identifier (client),
   and assembles them into a map containing the keys :topic, :msg and
   :target-client where the value of :topic is set to :log-msg and
   the client to which the message is directed becomes the value
   associated with the :target-client key.
   The map is then written to the server-messages-channel channel
   which will be subsequently 'swept' by a transport which actually
   sends the message to its destination.
   The client parameter is normally a key identifying the client,
   but it can be :all which results in a full broadcast.
   The value :log-msg is relevant as it's the selector
   for server-messages-publication."
  ([msg]
   (write-message-to-server-message-channel msg :all))
  ([msg client]
   (write-message-to-server-message-channel msg client nil))
  ([msg client options]
   (log/info
     "write-message-to-server-message-channel received "
     msg "for" client
     (if options
       (str "with options " (pr-str options))
       "without options."))
   ;; The server-messages-channel should be swept by a go block,
   ;; started when the server was started, that is responsible for
   ;; the mechanics of how the message on the channel is transferred
   ;; to the client e.g websocket, SSE or even redis pub/sub.
   (>!! server-messages-channel
        (merge
          {:topic         :log-msg
           :msg           msg
           :target-client client}
          (when (get options :message-type)
            {:message-type (get options :message-type)})))))

(defmacro with-forward-context
  ([body]
   (list `with-forward-context nil body))
  ([target-id body]
   (list `with-forward-context target-id body {}))
  ([target-id body options]
   (list 'do body
         (list
           `apply
           `write-message-to-server-message-channel
           (concat
             (list
               'list
               (list 'clojure.string/join " "
                     (conj
                       (map
                         #(if
                            (instance? java.lang.Throwable %)
                            "ERROR"
                            (list
                              'clojure.string/trim
                              (list
                                `str %)))
                         (rest body))
                       'list)))
             (if target-id
               (list target-id)
               '())
             (if options
               (list options)
               '()))))))

(defn convert-symbol-to-value
  [v & [N]]
  (str
    (if
      (and (symbol? v) (ns-resolve N v))
      (var-get (ns-resolve N v))
      v)))
