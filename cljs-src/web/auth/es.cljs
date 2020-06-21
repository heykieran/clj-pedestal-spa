(ns web.auth.es
  (:require [reagent.core :as reagent]
            [reagent.format :as ra-format]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [cljs-time.core :as t]
            [cljs-time.format :as tf]
            [web.auth.utils :as auth-utils]
            [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                               oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
            [goog.object]))

(rf/reg-event-db
  :auth/clear-database
  (fn[db _]
    (println "Clearing front-end database...")
    (-> db
        (update-in [:auth] dissoc :remote)
        (update-in [:auth] dissoc :external)
        (dissoc :remote)
        (dissoc :main)
        (dissoc :log-messages)
        (dissoc :sse-log-messages)
        (dissoc :test))))

(rf/reg-event-fx
  :auth/initialize-db-for-new-user
  (fn[cofx [_]]
    (js/console.log "Initializing DB for new user.")
    (let [db (:db cofx)]
      {:db db
       :dispatch-n
           [#_[:auth/clear-database]
            [:auth/get-logged-in-user]]})))

(rf/reg-event-fx
  :auth/get-logged-in-user-response-succeeded
  (fn [cofx [_ response]]
    (let
      [db (:db cofx)
       is-same-user?
       (auth-utils/same-user?
         response
         (get-in db [:auth :user]))]

      (println
        (str ":auth/get-logged-in-user returned "
             (pr-str response) ", which is "
             (if is-same-user?
               "the same user."
               (str "a different user from "
                    (pr-str (get-in db [:auth :user]))))))

      {:db
       (-> db
           (update-in [:auth :remote :getting-logged-in-user] dissoc :pending)
           (assoc-in [:auth :remote :getting-logged-in-user :result] response)
           (update-in [:auth :user] merge response)
           (update-in [:auth :user :connection-uuid]
                      (fn[ov nv]
                        (if ov
                          (do
                            (println "connection-uuid already set to" ov)
                            ov)
                          (do
                            (println "connection-uuid being set to " nv)
                            nv)))
                      (random-uuid)))
       :dispatch-n
       [(if-not is-same-user?
          [:auth/logged-in-user-changed response])]})))

(rf/reg-event-fx
  :auth/get-logged-in-user-response-failed
  (fn [cofx [_ response]]
    (let
      [db (:db cofx)]
      (println "In :auth/get-logged-in-user-response-failed with "
               (pr-str response))
      (merge
        {:db
         (-> db
             (update-in [:auth :remote :getting-logged-in-user] dissoc :pending)
             (assoc-in [:auth :remote :getting-logged-in-user :error] response)
             (update-in [:auth] dissoc :user))}
        (when
          (and (= (:status response) 401)
               (nil? (get-in response [:response :user])))
          {:dispatch
           [:auth/logged-in-user-changed nil]})))))

(rf/reg-event-fx
  :auth/log-out-user-response-succeeded
  (fn [cofx [_ response]]
    (let
      [db (:db cofx)]
      {:db
       (-> db
           (update-in [:auth :remote :logging-out-user] dissoc :pending)
           (assoc-in [:auth :remote :logging-out-user :result] response))
       :dispatch
       [:auth/logged-in-user-changed nil]})))

(rf/reg-event-fx
  :auth/log-out-user-response-failed
  (fn [cofx [_ response]]
    (let
      [db (:db cofx)]
      (println "In :auth/log-out-user-response-failed with "
               (pr-str response))
      {:db
       (-> db
           (update-in [:auth] dissoc :remote)
           (assoc-in [:auth :remote :logging-out-user :error] response)
           (assoc-in [:auth :user] nil))
       :dispatch-n
       [[:auth/clear-database]]
       :navigate-to
       [:app-home-page]})))


(rf/reg-event-fx
  :auth/logged-in-user-changed
  (fn [cofx [_ {user-id :user authority :authority
                client-id :client-id expires :expires
                ext-token :ext-token}]]
    (println "The signed-in user was changed to " (pr-str user-id))
    (let
      [db (:db cofx)]
      {:db (-> db
               (update-in
                 [:auth :user]
                 merge
                 {:user user-id
                  :authority authority
                  :client-id client-id :expires expires
                  :ext-token ext-token}))
       :dispatch-n
           [[:auth/clear-database]
            #_(if user-id [:maybe-initialize-something])]
       :navigate-to [:app-home-page]})))

(rf/reg-event-fx
  :auth/set-logged-in-user-response-succeeded
  (fn [cofx [_ response]]
    (let
      [db (:db cofx)]
      {:db (-> db
               (update-in [:auth :remote :setting-logged-in-user] dissoc :pending)
               (assoc-in [:auth :remote :setting-logged-in-user :result] response))
       :dispatch
           [:auth/logged-in-user-changed response]})))

(rf/reg-event-fx
  :auth/set-logged-in-user-response-failed
  (fn [cofx [_ response]]
    (let
      [db (:db cofx)]
      {:db
       (-> db
           (update-in [:auth :remote :setting-logged-in-user] dissoc :pending)
           (assoc-in [:auth :remote :setting-logged-in-user :error] response))})))

(rf/reg-sub
  :auth/logged-in-user
  (fn [db _]
    (-> db :auth :user)))

(rf/reg-event-fx
  :auth/set-external-authorized-info
  (fn[cofx [_ external-auth-service-id external-id]]
    (let [db (:db cofx)]
      {:db (->
             db
             (assoc-in
               [:auth :external external-auth-service-id :id]
               external-id))})))

(rf/reg-sub
  :auth/get-external-authorized-info
  (fn [db [_ external-auth-service-id]]
    (-> db :auth :external external-auth-service-id :id)))