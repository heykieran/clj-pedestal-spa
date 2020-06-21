(ns web.logs.ui
  (:require
    [reagent.core :as reagent]
    [re-frame.core :as rf]
    [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                       oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
    [cljs-time.core :as t]
    [cljs-time.format :as tf]
    [cljs-time.coerce :as tc]
    [web.forms.common :as sem]
    [web.logs.es]
    [web.logs.utils :as ws-utils]))

(defn log-holder-element-ui
  [{session-id :client-id
    user-id :user
    ext-token :ext-token
    connection-uuid :connection-uuid
    :as current-logged-in-user}]
  (let
    [timer-atom (atom nil)
     log-atom (atom nil)]
    (if connection-uuid
      (ws-utils/connect-ws-for-log
        user-id
        session-id
        connection-uuid
        ext-token
        log-atom)
      (println "No connection-uuid available for logging element "
               "during creation of component"))

    (reagent/create-class
      {:display-name
       :alloc-log-component

       :component-did-mount
       (fn[this]
         (println "Log Component didMount")
         (reset!
           timer-atom
           (js/setInterval
             (fn[]
               (if @log-atom
                 (ws-utils/send-ping
                   log-atom)))
             (* 1000 60 2))))

       :component-did-update
       (fn[this old-argv]
         (println "Log Component didUpdate")
         (let [new-argv (rest (reagent/argv this))
               {new-session-id :client-id
                new-connection-uuid :connection-uuid
                new-user-id :user
                new-ext-token :ext-token
                :as current-logged-in-user}
               (first new-argv)
               {existing-user-id :user
                existing-session-id :client-id}
               (first (rest old-argv))]
           (if (and (nil? new-session-id) existing-session-id)
             (do
               (println
                 (str "Clearing websocket for user "
                      (pr-str existing-user-id) ", "
                      "with client-id "
                      (pr-str existing-session-id)))
               (ws-utils/disconnect-web-socket-for-log log-atom)
               (reset! log-atom nil)))
           (if new-session-id
             (do
               (println
                 (str "Creating websocket for user "
                      (pr-str new-user-id)
                      "with client-id "
                      (pr-str new-session-id)))
               (ws-utils/connect-ws-for-log
                 new-user-id
                 new-session-id
                 new-connection-uuid
                 new-ext-token
                 log-atom)))))

       :component-will-unmount
       (fn[this]
         (println "Log Component willUnmount")
         (js/clearInterval @timer-atom)
         (reset! timer-atom nil)
         (reset! log-atom nil))

       :reagent-render
       (fn[{session-id :client-id :as current-logged-in-user}]
         [:div])})))

(defn log-item-ui
  [message-info]
  (fn[{msg-source :source message :message message-type :message-type}]
    (let
      [local-time
       (if (:time message)
         (tf/unparse-local
           (tf/formatter-local "YYYY-MM-dd HH:mm:ss")
           (t/to-default-time-zone
             (tc/from-string
               ;; make sure that milliseconds gets reduced to 3 digits only
               ;; otherwise posting won't work. The final character is
               ;; the sign of the tz offset
               (clojure.string/replace
                 (:time message)
                 #"\.(\d{3})(\d+)?([\-|\+])?"
                 ".$1$3"))))
         "0000-00-00 00:00:00")]
      [:div
       {:style {:font-weight :normal
                :color :black
                :display :flex}}
       [:div
        [:pre {:style {:margin :unset}} (str local-time)]]
       [:div {:style {:min-width "10px"}}]
       [:div
        {:style
         (merge
           {:font-family "Courier" :margin :inherit}
           (if (= :error message-type)
             {:color :red :font-weight :bold}))}
        (str (:text message)
             " [" msg-source "]")]])))

(defn log-page-ui
  [current-logged-in-user log-messages]
  (fn[current-logged-in-user log-messages]
    [:> sem/segment
     [:div {:style {:display :flex}}
      [:div {:style {:width "20%" :font-family "courier"}}
       [:strong
        (pr-str
          (:client-id @(rf/subscribe [:auth/logged-in-user])))]]
      [:div {:style {:width "70%"}}]
      [:div {:style {:text-align :right :width "10%"}}
       [:> sem/button {:size :mini
                   :basic true
                   :on-click
                   (fn[e d]
                     (rf/dispatch [:logs/clear-log]))}
        "Clear Log"]]]
     [:div
      (into
        [:div]
        (mapv
          (fn [msg]
            [log-item-ui msg])
          log-messages))]]))
