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
  (fn[cofx [_ current-logged-in-user which-one sec-type]]
    (js/console.log (str "Getting secured resource: "
                         (pr-str which-one)
                         " with sec type: "
                         (pr-str sec-type)))
    (js/console.log (pr-str current-logged-in-user))
    (let
      [db (:db cofx)
       token-sec? (= sec-type :token)
       use-credentials (not token-sec?)
       extra-headers (when
                       token-sec?
                       {:headers
                        {:x-debug-token
                         (:ext-token current-logged-in-user)
                         :response-session-id
                         (:client-id current-logged-in-user)}})
       target-uri
       (case
         which-one
         :admin
         (gpath/join
           api-urls/get-secured-resource-test-url
           (if token-sec? "a/h" "a"))
         :user
         (gpath/join
           api-urls/get-secured-resource-test-url
           (if token-sec? "u/h" "u"))
         (gpath/join
           api-urls/get-secured-resource-test-url
           (if token-sec? "p/h" "p")))]
      {:db
       (-> db
           (assoc-in [:test :remote :getting-secured-resource :pending] true)
           (update-in [:test :remote :getting-secured-resource] dissoc :error)
           (update-in [:test :remote :getting-secured-resource] dissoc :result))

       :http-xhrio
       (merge-with
         (fn[l r]
           (merge l r))
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
          [:test/get-secured-resource-response-failed]}
         extra-headers)})))
