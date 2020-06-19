(ns web.test.es
  (:require
    [re-frame.core :as rf]
    [goog.object]
    [web.test.remote]))

(rf/reg-event-fx
  :test/get-secured-resource-response-succeeded
  (fn [cofx [_ response]]
    (let
      [db (:db cofx)]
      {:db
       (-> db
           (update-in [:test :remote :getting-secured-resource] dissoc :pending)
           (assoc-in [:test :remote :getting-secured-resource :result] response))})))

(rf/reg-event-fx
  :test/get-secured-resource-response-failed
  (fn [cofx [_ response]]
    (let
      [db (:db cofx)]
      {:db
       (-> db
           (update-in [:test :remote :getting-secured-resource] dissoc :pending)
           (assoc-in [:test :remote :getting-secured-resource :error] response))})))

(rf/reg-sub
  :test/get-pass-fail
  (fn[db [_]]
    (let [{res :result err :error} (-> db :test :remote :getting-secured-resource)]
      (cond
        (some? res)
        {:result :ok :message "Request returned (OK)." :payload res}
        (some? err)
        {:result :fail :message "Request returned (FAIL)." :payload err}
        :else
        {:result :pending
         :message "Waiting for response...."
         :payload ""}))))