(ns web.home.ui
  (:require
    [reagent.core :as reagent]
    [re-frame.core :as rf]
    [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                       oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
    [cljs-time.core :as t]
    [kee-frame.core :as k]
    [web.forms.common :as sem]
    [web.auth.utils :as auth-utils]))

(defn home-ui
  [current-logged-in-user]
  (fn[current-logged-in-user]
    (if-not
      (auth-utils/is-logged-in? current-logged-in-user)
      [:> sem/message {:icon true :floating true :warning true}
       [:> sem/icon {:name :lock}]
       [:> sem/message-content
        [:> sem/message-header "Not Signed In"]
        [:<>
         (str "Your session has not been authorized. You must sign in before
       you can use the application - ")]
        [:a {:href (k/path-for [:auth-login-page])}
         "Sign-In"]]]

      [:> sem/message {:icon true :floating true :warning true}
       [:> sem/icon {:name :lock}]
       [:> sem/message-content
        [:> sem/message-header "Signed In"]
        [:<>
         (str "Your session is authorized. You are signed in as "
              "user " (:user current-logged-in-user) ", "
              "with authority " (:authority current-logged-in-user) ". "
              "Your connection ID is " (:client-id current-logged-in-user))]]])))
