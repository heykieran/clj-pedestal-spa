(ns web.auth.ui
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require
    [reagent.core :as reagent]
    [reagent.dom :as reagent-dom]
    [reagent.cookies :as reagent-cookies]
    [re-frame.core :as rf]
    [cljs.core.async :as a :refer [<! >!]]
    [ajax.core :refer [GET POST]]
    [goog.dom :as dom]
    [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                       oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
    [cljs-time.core :as t]
    [web.forms.common :as sem]
    [web.auth.es]
    [config.client :as config]))

;; Much of what's below is taken from https://github.com/tristanstraub/cljs-google-signin
;; Much gratitude.
;;

(defn- <cb
  "Call an callback style function and return a channel containing the result of calling the callback"
  [f & args]
  (let
    [out (a/chan)]
    (apply
      f
      (conj
        (into [] args)
        #(a/close! out)))
    out))

(defn- <promise
  "Call a function that returns a promise and convert it into a channel"
  [f & args]
  (let
    [out  (a/chan)
     done (fn [& _] (a/close! out))]
    (.then
      (apply f args)
      done
      done)
    out))

(defn- render-signin-button
  "Get gapi to render sign-in button within the provided container element"
  [el & {:keys [on-success on-failure]}]
  (let
    [auth-instance (ocall js/gapi.auth2 "getAuthInstance")
     is-signed-in? (-> auth-instance
                       (oget "isSignedIn")
                       (ocall "get"))
     signed-in-user (if is-signed-in?
                      (-> auth-instance
                          (oget "currentUser")
                          (ocall "get")))
     signed-in-user-email (if is-signed-in?
                      (-> signed-in-user
                          (ocall "getBasicProfile")
                          (ocall "getEmail")))]
    (println
      (str "In render-signin-button. is-signed-in? = "
           (pr-str is-signed-in?) ", "
           "google-user = " (pr-str signed-in-user-email)))

    (when (and is-signed-in? signed-in-user-email)
      (rf/dispatch
        [:auth/set-external-authorized-info
         :google
         signed-in-user-email]))
    (if
      is-signed-in?
      (reagent-dom/render
        [:> sem/button
         (merge
           {:color :green}
           {:on-click
            (fn[]
              (on-success signed-in-user))})
         "Continue" ]
        el)
      (js/gapi.signin2.render
        el
        #js {"scope"     "profile email"
             "width"     144
             "height"    30
             "longtitle" false
             "theme"     "dark"
             "onsuccess" on-success
             "onfailure" on-failure}))))

(defn- render-signout-button
  [el & [on-sign-out]]
  (let
    [auth-instance (ocall js/gapi.auth2 "getAuthInstance")
     is-signed-in? (-> auth-instance
                       (oget "isSignedIn")
                       (ocall "get"))
     render-button
     (fn[el is-signed-in?]
       (reagent-dom/render
         [:> sem/button
          (merge
            {:color :red}
            (if (not is-signed-in?) {:disabled true})
            (if on-sign-out {:on-click on-sign-out} {}))
          "Sign Out" ]
         el))]

    (render-button el is-signed-in?)

    (println "Adding listener in render-signout-button")
    (-> auth-instance
        (oget "isSignedIn")
        (ocall "listen"
               (fn[signed-in?]
                 (println "Google listener says " (pr-str signed-in?))
                 (render-button el signed-in?))))))

(defn- <post-to-server
  [token]
  (let
    [form-data
     (str "idtoken=" token "&connection-uuid=" (random-uuid))]
    (POST
      config/google-callback-url
      {:body form-data
       :response-format :raw
       :with-credentials true
       :handler (fn[r]
                  (js/console.log "Server Response from Google Login")
                  (js/console.log r)
                  (js/console.log "will confirm with server")
                  (rf/dispatch
                    [:auth/get-logged-in-user]))
       :error-handler (fn[e]
                        (js/console.log "Server Error")
                        (js/console.log e))})))

(defn- <load-script
  [url]
  (go
    (let
      [s (dom/createElement "script")]
      (set! (.-src s) url)
      (let
        [loaded (<cb (fn [cb] (set! (.-onload s) cb)))]
        (.appendChild (.-body js/document) s)
        (<! loaded)))))

(defn- <init-gapi!
  "Initialise gapi library"
  [client-id]
  (assert client-id)
  (go
    (<! (<cb js/gapi.load "auth2"))
    (<! (<promise
          js/gapi.auth2.init
          #js {:client_id client-id}))))

(defn- <ensure-gapi!
  [client-id]
  (go
    (when-not (exists? js/gapi)
      (<!
        (<load-script
          config/google-script-location)))
    (<! (<init-gapi! client-id))))

(defn <sign-out!
  "Sign user out of gapi session"
  []
  (<promise
    #(.signOut
       (js/gapi.auth2.getAuthInstance))))

(defn google-login-button
  [client-id on-success on-failure on-sign-out]
  (let
    [gapi-loaded (atom nil)]
    (reagent/create-class
      {:display-name
       :google-login-button
       :component-did-mount
       (fn[this]
         (let
           [el (reagent-dom/dom-node this)
            b-el (ocall el "querySelector" ".belem")
            c-el (ocall el "querySelector" ".celem")]
           (a/go
             (<!
               (<ensure-gapi! client-id))
             (render-signin-button
               b-el
               :on-success on-success
               :on-failure on-failure)
             (render-signout-button
               c-el
               on-sign-out)))
         this)
       :reagent-render
       (fn[client-id on-success on-failure on-sign-out]
         [:div {:style {:display :flex}}
          [:div.belem]
          [:div.celem {:style {:padding-left "10px"}}]])})))


(defn main-login-ui
  [current-logged-in-user]
  (fn[current-logged-in-user]
    (println "The current logged in user is "
             (pr-str current-logged-in-user))
    [:> sem/segment
     [:> sem/header {:as "h2"} "Sign-In Page"]
     [:<>
      (let
        [s-user (:user current-logged-in-user)
         s-authority (:authority current-logged-in-user)]
        [:> sem/container
         [:> sem/header {:as "h3"} "Test Users"]
         [:> sem/message "Select a user below to utilize a 'local' (i.e. test) account."]
         [:> sem/grid
          {:columns :equal}
          [:> sem/grid-row
           {:text-align :center}
           [:> sem/grid-column
            {:width 8}
            [:> sem/button
             {:icon true
              :label-position :left
              :disabled s-user
              :on-click
              (fn[e d]
                (rf/dispatch
                  [:auth/set-logged-in-user
                   "admin@timpsongray.com"]))}
             [:> sem/icon
              {:name :user
               :color :green}]
             "admin@timpsongray.com"]]
           [:> sem/grid-column
            {:width 8}
            [:> sem/button
             {:icon true
              :label-position :left
              :disabled s-user
              :on-click
              (fn[e d]
                (rf/dispatch
                  [:auth/set-logged-in-user
                   "user@timpsongray.com"]))}
             [:> sem/icon
              {:name :user
               :color :green}]
             "user@timpsongray.com"]]]]

         [:> sem/header {:as "h3"} "Google Users"]
         [:> sem/message
          (let
            [external-user-id @(rf/subscribe [:auth/get-external-authorized-info :google])]
            (if external-user-id
              (str "The app is already registered to use your Google account "
                   external-user-id ". Click continue to attach this session with that ID, "
                   "or Sign-Out to log out.")
              (str "Use the sign in button below to use your Google account to sign-in to the application.")))]
         [google-login-button
          config/google-client-id
          (fn [google-user]
            (println "running google-login-button on-success function")
            (let
              [token (some->
                       google-user
                       (ocall "getAuthResponse")
                       (oget "id_token"))]
              (when token
                (<post-to-server
                  token))))
          (fn [& args]
            (println "running google-login-button on-fail function")
            (println "Failed"))
          (fn[& args]
            (println "In google sign out function with "
                     (pr-str current-logged-in-user))
            (<sign-out!)
            (rf/dispatch
              [:auth/log-out-user
               (:user current-logged-in-user)]))]

         (if
           (and
             s-user
             (= :local s-authority))
           [:<>
            [:> sem/header {:as "h3"} (str "Sign Out (Local)")]
            [:> sem/message "You are signed in under a local account. Use the sign out button below to disconnect your session."]
            [:> sem/button
             {:icon true
              :size :large
              :on-click (fn[e d]
                          (rf/dispatch
                            [:auth/log-out-user
                             (:user current-logged-in-user)]))}
             [:> sem/icon
              {:size :large
               :name "sign-out"}]]])])]]))

