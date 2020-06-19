(ns web.test.remote
  (:require
    [re-frame.core :as rf]
    [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                       oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
    [goog.object]
    [server.transit-handlers :as tr-handlers]
    [ajax.core :as ajax]
    [cljs-time.core :as t]
    [web.default-api-urls :as api-urls]
    [goog.string.path :as gpath]))

(rf/reg-event-fx
  :test/get-secured-resource
  (fn[cofx [_ current-logged-in-user which-one]]
    (js/console.log (str "Getting secured resource: "
                         (pr-str which-one)))
    (js/console.log (pr-str current-logged-in-user))
    (let
      [db (:db cofx)
       use-credentials true 
       target-uri
       (case
         which-one
         :admin
         (gpath/join
           api-urls/get-secured-resource-test-url
           "a")
         :user
         (gpath/join
           api-urls/get-secured-resource-test-url
           "u")
         (gpath/join
           api-urls/get-secured-resource-test-url
           "p"))]
      {:db
       (-> db
           (assoc-in [:test :remote :getting-secured-resource :pending] true)
           (update-in [:test :remote :getting-secured-resource] dissoc :error)
           (update-in [:test :remote :getting-secured-resource] dissoc :result))

       :http-xhrio
       {:method :post
        :format (ajax/transit-request-format
                 {:writer
                  (cognitect.transit/writer
                   :json
                   {:handlers tr-handlers/writers})})
        :uri target-uri
        :params {:placeholder 1}
        :with-credentials use-credentials
        :timeout 5000
        :response-format
        (ajax/transit-response-format
         {:reader
          (cognitect.transit/reader
           :json
           {:handlers tr-handlers/readers})})
        :on-success
        [:test/get-secured-resource-response-succeeded]
        :on-failure
        [:test/get-secured-resource-response-failed]}})))
