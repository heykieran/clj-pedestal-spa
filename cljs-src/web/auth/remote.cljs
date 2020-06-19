(ns web.auth.remote
  (:require
    [re-frame.core :as rf]
    [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                       oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
    [goog.object]
    [server.transit-handlers :as tr-handlers]
    [ajax.core :as ajax]
    [cljs-time.core :as t]
    [web.default-api-urls :as api-urls]))

(rf/reg-event-fx
  :auth/get-logged-in-user
  (fn[cofx [_ current-logged-in-user]]
    (println "Getting logged in user from server")
    (let
      [db (:db cofx)]
      {:db
       (-> db
           (assoc-in [:auth :remote :getting-logged-in-user :pending] true)
           (update-in [:auth :remote :getting-logged-in-user] dissoc :error)
           (update-in [:auth :remote :getting-logged-in-user] dissoc :result))

       :http-xhrio
       {:method          :post
        :format (ajax/transit-request-format
                  {:writer
                   (cognitect.transit/writer
                     :json
                     {:handlers tr-handlers/writers})})
        :uri             api-urls/current-user-auth-url
        :params          {:placeholder 1}
        :with-credentials true
        :timeout         5000
        :response-format (ajax/transit-response-format
                           {:reader
                            (cognitect.transit/reader
                              :json
                              {:handlers tr-handlers/readers})})
        :on-success      [:auth/get-logged-in-user-response-succeeded]
        :on-failure      [:auth/get-logged-in-user-response-failed]}})))

(rf/reg-event-fx
  :auth/set-logged-in-user
  (fn[cofx [_ email-address]]
    (let
      [db (:db cofx)]
      {:db
       (-> db
           (assoc-in [:auth :remote :setting-logged-in-user :pending] true)
           (update-in [:auth :remote :setting-logged-in-user] dissoc :error)
           (update-in [:auth :remote :setting-logged-in-user] dissoc :result))

       :http-xhrio
       {:method          :post
        :format (ajax/transit-request-format
                  {:writer
                   (cognitect.transit/writer
                     :json
                     {:handlers tr-handlers/writers})})
        :uri             api-urls/set-current-user-auth-url
        :params          {:email email-address}
        :with-credentials true
        :timeout         5000
        :response-format (ajax/transit-response-format
                           {:reader
                            (cognitect.transit/reader
                              :json
                              {:handlers tr-handlers/readers})})
        :on-success      [:auth/set-logged-in-user-response-succeeded]
        :on-failure      [:auth/set-logged-in-user-response-failed]}})))

(rf/reg-event-fx
  :auth/log-out-user
  (fn[cofx [_ user-id]]
    (println "Logging out user " (pr-str user-id) " in :auth/log-out-user")
    (let
      [db (:db cofx)]
      {:db
       (-> db
           (assoc-in [:auth :remote :logging-out-user :pending] true)
           (update-in [:auth :remote :logging-out-user] dissoc :error)
           (update-in [:auth :remote :logging-out-user] dissoc :result))

       :http-xhrio
       {:method          :post
        :format (ajax/transit-request-format
                  {:writer
                   (cognitect.transit/writer
                     :json
                     {:handlers tr-handlers/writers})})
        :uri             api-urls/log-out-user-auth-url
        :params          {:user-id user-id}
        :with-credentials true
        :timeout         5000
        :response-format (ajax/transit-response-format
                           {:reader
                            (cognitect.transit/reader
                              :json
                              {:handlers tr-handlers/readers})})
        :on-success      [:auth/log-out-user-response-succeeded]
        :on-failure      [:auth/log-out-user-response-failed]}})))