(ns web.sse-logs.es
  (:require [reagent.core :as reagent]
            [reagent.format :as ra-format]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [clojure.string :as str]
            [cljs-time.core :as t]
            [cljs-time.format :as tf]
            [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                               oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
            [goog.object]))

(rf/reg-sub
  :sse-logs/log-messages
  (fn[db [_]]
    (get-in db [:sse-log-messages])))

(rf/reg-event-fx
  :sse-logs/add-message-to-log
  (fn[cofx [_ source message message-type]]
    (let
      [db (:db cofx)]
      {:db
       (-> db
           (update-in
             [:sse-log-messages]
             (fnil conj [])
             {:source source
              :message message
              :message-type (or message-type :info)}))})))

(rf/reg-event-fx
  :sse-logs/clear-log
  (fn[cofx [_]]
    (let
      [db (:db cofx)]
      {:db
       (-> db
           (assoc-in
             [:sse-log-messages]
             [{:source :client
               :message {:time nil :text "Log Cleared"}}]))})))
