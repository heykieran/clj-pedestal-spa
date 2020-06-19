(ns web.reframe
  (:require [reagent.core :as reagent]
            [reagent.format :as ra-format]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [cljs-time.core :as t]
            [cljs-time.format :as tf]
            [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                               oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
            [goog.object]
            [ajax.core :as ajax]
            [kee-frame.core :as k]
            [web.auth.es]
            [web.auth.remote]
            [web.main.ui :as main-ui]
            [web.test.ui :as test-ui]
            [web.home.ui :as home-ui]))

(enable-console-print!)

(rf/reg-sub
  :error
  (fn [db _]
    (-> db :error)))

(rf/reg-sub
  :asset-error
  (fn [db _]
    (-> db :asset-error)))

(rf/reg-sub
  :application-errors
  (fn[db _]
    (get-in db [:application-errors])))

(rf/reg-sub
  :user-application-errors
  (fn[[_ id] _]
    [(rf/subscribe[:application-errors])])
  (fn[[application-errors] [_ id]]
    (let
      [app-errors
       (keep
         (fn[{client-id :client-id :as a-err}]
           (if (= client-id id)
             a-err))
         application-errors)]
      (if (empty? app-errors)
        nil
        (into
          []
          app-errors)))))

(rf/reg-event-fx
  :clear-application-errors
  (fn [cofx [_]]
    (let
      [db (:db cofx)]
      {:db (update db dissoc :application-errors)})))

(rf/reg-event-fx
  :clear-user-application-error
  (fn [cofx [_ id]]
    (let
      [db (:db cofx)]
      {:db
       (assoc
         db
         :application-errors
         (into
           []
           (keep
             (fn[{client-id :client-id :as a-err}]
               (if (not= client-id id)
                 a-err))
             (:application-errors db))))})))

(defn unknown-ui
  [route]
  (fn[route]
    [:<>
     [:div "UNKNOWN"
      [:div (pr-str route)]]]))

(defn main-page
  []
  (fn[]
    (let [route @(rf/subscribe [:kee-frame/route])
          path-params (get route :path-params)
          current-logged-in-user @(rf/subscribe [:auth/logged-in-user])]
      [:<>
       [:div
        (case
          (get-in route [:data :name])
          :setup-page-main-item [main-ui/main-ui (keyword :setup (:id path-params)) current-logged-in-user]
          :app-home-page [main-ui/main-ui :home current-logged-in-user]
          :auth-login-page [main-ui/main-ui (keyword :auth :manage) current-logged-in-user]
          [unknown-ui route])]])))

(def routes
  [["/r/home" :app-home-page]
   ["/r/app/setup/:id" :setup-page-main-item]
   ["/r/app/auth/login" :auth-login-page]])

(rf/reg-event-fx
  :go-to-home-page
  (fn [_ [_ current-logged-in-user]]
    (println "In :go-to-home-page is reframe.cljs")
    {:navigate-to [:app-home-page]
     :dispatch-n [[:auth/get-logged-in-user current-logged-in-user]]
     }))

(rf/reg-event-fx
  :go-to-sign-in-page
  (fn [_ [_ current-logged-in-user]]
    (println
      (str "In :go-to-sign-in-page is reframe.cljs with "
           (pr-str current-logged-in-user)))
    {:navigate-to [:auth-login-page]
     :dispatch-n [[:auth/get-logged-in-user
                   current-logged-in-user]]}))

(rf/reg-event-fx
  :sign-out-of-app
  (fn [_ [_ current-logged-in-user]]
    (println
      (str "In :sign-out-of-app is reframe.cljs with "
           (pr-str current-logged-in-user)))
    {:navigate-to [:auth-login-page]}))

(rf/reg-event-fx
  :setup-main-item
  (fn [_ [_ current-logged-in-user item-id]]
    {:dispatch
     [:test/get-secured-resource
      current-logged-in-user
      item-id]
     :navigate-to
     [:setup-page-main-item
      {:id item-id}]}))

(k/reg-controller
  :initial-env-info-controller
  {:params (constantly true)
   :start  (fn[ctx _]
             [:auth/initialize-db-for-new-user])})

(defn ^:export init
  []
  (rf/clear-subscription-cache!)
  (k/start!  {:routes         routes
              :initial-db     {}
              :root-component [main-page]
              :debug?         true}))



