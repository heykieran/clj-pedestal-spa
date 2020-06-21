(ns web.main.ui
  (:require
    [reagent.core :as reagent]
    [re-frame.core :as rf]
    [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                       oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
    [cljs-time.core :as t]
    [web.forms.common :as sem]
    [web.default-api-urls :as api-urls]
    [web.test.ui :as test-ui]
    [web.auth.ui :as m-auth-ui]
    [web.home.ui :as home-ui]
    [web.auth.utils :as auth-utils]))

(defn get-setup-menu-items
  [hide-sidebar current-logged-in-user]
  (let
    [menu-item-descs
     [[:public :session "Public" :user]
      [:user :session "User" :user]
      [:admin :session "Admin" :user]
      [:public :token "Public (t)" :user]
      [:user :token "User (t)" :user]
      [:admin :token "Admin (t)" :user]]]
    (into
      [:<>
       [:> sem/menu-item
        {:as "a"
         :on-click
             (fn[e d]
               (hide-sidebar)
               (rf/dispatch [:go-to-home-page]))}
        [:> sem/icon {:name :home}]
        "Home"]
       [:> sem/menu-item
        {:as "a"
         :on-click
             (fn[e d]
               (hide-sidebar)
               (rf/dispatch
                 [:go-to-sign-in-page
                  current-logged-in-user]))}
        [:> sem/icon {:name :lock}]
        "Users"]]
      (mapv
        (fn[[type-kw sec-type text icon-name]]
          [:> sem/menu-item
           {:as "a"
            :on-click
                (fn[e d]
                  (hide-sidebar)
                  (rf/dispatch
                    [:setup-main-item
                     current-logged-in-user
                     sec-type type-kw]))}
           [:> sem/icon {:name icon-name}]
           text])
        menu-item-descs))))

(defn main-ui
  [item-id current-logged-in-user]
  (let
    [
     visible (reagent.core/atom :none)
     show-sidebar
     (fn[mode]
       (if (= mode @visible)
         (reset! visible :none)
         (reset! visible mode)))
     hide-sidebar
     (fn[]
       (reset! visible :none))]
    (fn[item-id current-logged-in-user]
      [:<>
       [:> sem/grid
        {:columns 2
         :padded :horizontally
         :vertical-align :middle}
        [:> sem/grid-column
         [:> sem/header
          {:as :h2
           :floated :left}
          [:> sem/image
           {:src "timpson-gray-small.png"
            :width 100
            :height 100
            :vertical-align :middle
            :on-click (fn[e d]
                        (rf/dispatch [:go-to-home-page]))}]
          [:> sem/header-content
           "Login Application"]]]

        [:> sem/grid-column
         {:vertical-align :middle}
         [:> sem/header
          {:as :h2
           :floated :right}
          (if (auth-utils/is-logged-in? current-logged-in-user)
            [:> sem/header-content
             [:> sem/grid
              {:vertical-align :middle
               :columns 4}
              [:> sem/grid-column
               {:float :right}
               [:> sem/icon
                {:name "sign-out"
                 :color :red
                 :link true
                 :on-click (fn[e d]
                             (rf/dispatch
                               [:sign-out-of-app]))}]]
              [:> sem/grid-column
               {:float :left}
               [:small
                (auth-utils/logged-in-user-description
                  current-logged-in-user)]]]]
            [:> sem/header-content
             [:> sem/icon
              {:name "sign-in"
               :link true
               :color :green
               :on-click (fn[e d]
                           (rf/dispatch
                             [:go-to-sign-in-page]))}]])]]]

       [:> sem/divider {:hidden true :fitted true}]

       [:div {:style {:padding-left "8px" :padding-top "4px"}}
        [:div
         [:> sem/button
          {:on-click (partial show-sidebar :setup)
           :icon true
           :disabled false #_(not (auth-utils/is-logged-in? current-logged-in-user))}
          [:> sem/icon {:name :bars}]]]]

       [:> sem/sidebar-pushable
        [:> sem/sidebar
         {:as sem/menu
          :animation :overlay
          :icon :labelled
          :inverted true
          :vertical true
          :visible (not (= :none @visible))
          :width :thin}
         (case @visible
           :none [:div]
           :setup [get-setup-menu-items
                   hide-sidebar
                   current-logged-in-user])]
        [:> sem/sidebar-pusher
         [:> sem/segment {:basic true}
          (case item-id
            :home
            [home-ui/home-ui current-logged-in-user]
            :setup/public
            [test-ui/public-ui]
            :setup/user
            [test-ui/user-ui]
            :setup/admin
            [test-ui/admin-ui]
            :auth/manage
            [m-auth-ui/main-login-ui current-logged-in-user]
            [:> sem/segment
             (str "BLANK WITH " item-id ". Are you missing something?")])]]]])))



