(ns server.be-handler-pdstl
  (:require
    [clojure.pprint :as pprint]
    [io.pedestal.http :as http]
    [io.pedestal.http.route :as route]
    [clojure.tools.logging :as log]
    [io.pedestal.http.content-negotiation :as conneg]
    [server.transit-handlers :as tr-handlers]
    [buddy.auth]
    [hiccup.core :refer [html]]
    [ring.middleware.session.cookie :refer [cookie-store]]
    [io.pedestal.http.ring-middlewares :as middlewares]
    [server.auth.base :as app-auth]
    [config.server :as server-config]
    [java-time :as time])
  (:import (org.eclipse.jetty.server Server)))

(def default-content-security-policy
  (str "default-src 'self'; "
       "connect-src 'self' ws://localhost:* wss://localhost:* https://localhost:* " "wss://" server-config/host ":* " server-config/my-hostname ":*; "
       "img-src 'self' " server-config/my-hostname ":* " server-config/my-hostname ":*; "
       "script-src 'self' " server-config/my-hostname ":*" " https://apis.google.com 'unsafe-inline';"
       "script-src-elem 'self' " server-config/my-hostname ":*" " https://apis.google.com https://cdn.jsdelivr.net https://code.jquery.com 'unsafe-inline';"
       "font-src 'self' data: https://fonts.gstatic.com https://cdn.jsdelivr.net;"
       "child-src 'self' https://accounts.google.com; "
       "style-src 'self' https://accounts.google.com https://apis.google.com https://fonts.googleapis.com https://cdn.jsdelivr.net/npm/semantic-ui@2.4.2/dist/semantic.min.css 'unsafe-inline';"))

(defn response [status body & [headers :as hdrs]]
  {:status status
   :body body
   :headers (->>
              (if
                (map? headers)
                (mapcat (fn [[k v]] [(name k) (str v)]) headers)
                hdrs)
              (apply hash-map))})

(def ok       (partial response 200))
(def created  (partial response 201))
(def accepted (partial response 202))
(def error    (partial response 500))
(defn not-found []
  {:status 404 :body "Not found\n"})

(def echo
  {:name ::echo
   :enter (fn [context]
            (let [request (:request context)
                  response (ok request "Content-Type" "text/plain")]
              (assoc context :response response)))})

(def health-report
  {:name ::health-report
   :enter (fn [context]
            (let [request (:request context)
                  response
                  (ok
                    (with-out-str
                      (pprint/pprint
                        (select-keys
                          request
                          [:protocol :async-supported? :remote-addr
                           :headers :server-port :path-info :uri
                           :server-name :scheme :request-method
                           :query-string :path-params])))
                    "Content-Type" "text/plain")]
              (assoc context :response response)))})

(defn landing-page
  [request]
  "Use public for protected, use httpdocs for unprotected"
  (let
    [base-href "/"
     base-doc-loc "" ;; or "public" or "httpdocs"
     js-loc (str base-doc-loc "/cljs-out/") ;; or  "/js/"
     css-loc (str base-doc-loc "/css/")
     content-loc (str base-doc-loc "") ] ;; or "/content/"
    (ok
      (html
        [:html
         {:lang "en"}
         [:head
          [:meta {:charset "UTF-8" :name "referrer" :content "origin"}]
          [:base {:href base-href}]
          [:title "Timpson Gray Accounting"]
          [:link
           {:rel "stylesheet"
            :href "https://cdn.jsdelivr.net/npm/semantic-ui@2.4.2/dist/semantic.min.css"
            :type "text/css"}]
          [:link
           {:rel "stylesheet"
            :href (str css-loc "app-style.css")
            :type "text/css"}]]
         [:body
          [:div.ui.container
           [:h1.ui.huge.header
            [:a.ui.image
             {:href "https://timpsongray.com"}
             [:img.ui.image {:src "timpson-gray-small.png"}]]
            [:div.content "Timpson Gray Accounting"
             [:div.ui.sub.header.gray "Demo Site"]]]

           [:div.ui.icon.info.message
            [:i.info.blue.circle.icon]
            [:div.content
             [:div.header "Note"]
             [:p
              [:span
               "This is a demo site of the "
               [:b "Timpson Gray"]
               " Accounting System "
               "for Private Equity Fund Managers."]
              [:p (str "If you would like access to your own demo system "
                       "please contact us at ")]
              [:a {:href "mailto:info@timpsongray.com"} "info@timpsongray.com"]]]]

           [:div.ui.padded.segment
            [:div.ui.two.column.stackable.center.aligned.grid
            [:div.middle.aligned.row
             [:div.column
              [:div.row
               [:div.ui.icon.header
                [:i.green.arrow.circle.right.icon
                 {"onClick" "window.location.href='/r/home';"}]]]
              [:div.row
               [:div.ui.primary.button
                {"onClick" "window.location.href='/r/home';"}
                "Application"]]]
             [:div.column
              [:div.row
               [:div.ui.icon.header
                [:i.book.icon
                 {"onClick" "window.location.href='https://docs.timpsongray.com';"}]]]
              [:div.row
               [:div.ui.primary.button
                {"onClick" "window.location.href='https://docs.timpsongray.com';"}
                "Documentation"]]]]]]

           [:div.ui.inverted.bottom.center.aligned.segment
            [:div (str "Timpson Gray - &copy; " (time/year (time/local-date))
                       " - id:" server-config/app-build-id)]]]]

         [:script {:src "https://code.jquery.com/jquery-3.1.1.min.js"
                   :integrity "sha256-hVVnYaiADRTO2PzUGmuLJr8BLUSjGIZsDYGmIJLv2b8="
                   :crossorigin="anonymous"}]
         [:script {:src "https://cdn.jsdelivr.net/npm/semantic-ui@2.4.2/dist/semantic.min.js"}]])
      "Content-Type"
      "text/html"
      "Access-Control-Allow-Credentials" "false"
      "Content-Security-Policy"
      default-content-security-policy)))

(defn get-secured-resource
  [request]
  (let
    [session-id (get request :asys/session-id)]
    (log/info "Getting secured resource"
              "session-id is " session-id)
    (ok
      {:the-results
       "Let's pretend that this is something interesting."})))

(def supported-types
  ["text/html" "application/edn"
   "application/json" "text/plain"
   "application/transit+json" "text/event-stream"])

(def content-neg-intc
  (conneg/negotiate-content supported-types))

(def allocations-transit-body-interceptor
  (http/transit-body-interceptor
    ::allocations-transit-json-body
    "application/transit+json;charset=UTF-8"
    :json
    {:handlers tr-handlers/writers
     :transform (fn[from] from)}))

(def allocations-body-params-interceptor
  (io.pedestal.http.body-params/body-params
    (io.pedestal.http.body-params/default-parser-map
      :transit-options [{:handlers tr-handlers/readers}])))

(def base-interceptors
  [content-neg-intc
   route/query-params
   allocations-transit-body-interceptor
   allocations-body-params-interceptor])

(def common-interceptors
  (conj
    base-interceptors
    (middlewares/session
      {:store
       (cookie-store
         {:key server-config/session-store-key})})))

(def alloc-auth-interceptors
  [(app-auth/expire-identity-interceptor)
   (app-auth/alloc-auth-authentication-interceptor
    app-auth/alloc-auth-session-auth-backend)
   (app-auth/alloc-auth-user-roles-interceptor)
   (app-auth/alloc-auth-unauthorized-interceptor)
   (app-auth/alloc-auth-permission-checker-interceptor-factory)
   (app-auth/alloc-auth-client-session-id-tagger-interceptor-factory)])

(def common+auth-interceptors
  (into
    []
    (concat
      common-interceptors
      alloc-auth-interceptors)))

(defn build-secured-route-vec-to
  [handler]
  (into
    []
    (concat
      common+auth-interceptors
      [handler])))

(defn respond-with-app-page
  [request]
  "Use public for protected, use httpdocs for unprotected"
  (let
    [base-href "/"
     base-doc-loc "" ;; or "public" or "httpdocs"
     js-loc (str base-doc-loc "/cljs-out/") ;; or  "/js/"
     css-loc (str base-doc-loc "/css/")
     content-loc (str base-doc-loc "") ] ;; or "/content/"
    (ok
      (html
        [:html
         {:lang "en"}
         [:head
          [:meta {:charset "UTF-8" :name "referrer" :content "origin"}]
          [:meta {:name "google-signin-client_id" :content server-config/google-client-id}]
          [:base {:href base-href}]
          [:title "Timpson Gray Application"]
          [:link
           {:rel "stylesheet"
            :href "https://cdn.jsdelivr.net/npm/semantic-ui@2.4.2/dist/semantic.min.css"
            :type "text/css"}]
          [:link
           {:rel "stylesheet"
            :href (str css-loc "app-style.css")
            :type "text/css"}]]
         [:body
          [:div#app
           [:div "If you see this you'll need to login"]]
          [:script {:src (str js-loc "prod-main.js")
                    :type "application/javascript" :defer true}]
          [:script {:src (str content-loc "initialize-app.js")
                    :type "application/javascript" :defer true}]]])
      "Content-Type"
      "text/html"
      "Access-Control-Allow-Credentials" "true"
      "Content-Security-Policy"
      default-content-security-policy)))

(def routes
  (route/expand-routes
    #{["/" :get landing-page :route-name :landing-page]
      ["/healthz" :get health-report :route-name :health-check]
      ["/echo"  :get echo :route-name :echo]
      ["/auth/isauthenticated" :post (build-secured-route-vec-to app-auth/get-current-logged-in-user) :route-name :alloc-public/is-authenticated]
      ["/auth/setid" :post (build-secured-route-vec-to app-auth/alloc-auth-explicitly-set-identity-of-user-post) :route-name :alloc-public/auth-set-id-post]
      ["/auth/google" :post (build-secured-route-vec-to app-auth/alloc-auth-google-login) :route-name :alloc-public/google-login-post]
      ["/auth/logout" :post (build-secured-route-vec-to app-auth/disconnect-session) :route-name :alloc-user/auth-logout-post]
      ["/api/getsecresource/p" :post (build-secured-route-vec-to get-secured-resource) :route-name :alloc-public/test-res]
      ["/api/getsecresource/u" :post (build-secured-route-vec-to get-secured-resource) :route-name :alloc-user/test-res]
      ["/api/getsecresource/a" :post (build-secured-route-vec-to get-secured-resource) :route-name :test-res]
      ["/r/home" :get [content-neg-intc respond-with-app-page] :route-name :app-main-page]}))

(app-auth/build-permissions routes)
(app-auth/build-users-table server-config/roles-and-users)

(def service-map
  {::http/host  "0.0.0.0"
   ::http/allowed-origins
               {:allowed-origins (fn[_] true)
                :creds true}
   ::http/routes #(deref #'routes)
   ::http/resource-path "/public"
   ::http/type   :jetty
   ::http/container-options
   {:h2c? true
    :h2 true
    :ssl? true
    :ssl-port server-config/server-ssl-port
    :keystore server-config/keystore-location
    :key-password server-config/keystore-password
    :security-provider "Conscrypt"}
   ::http/port   server-config/server-port})

(defonce server-dev (atom nil))
(defonce server-prod (atom nil))

(defn stop-dev []
  (http/stop @server-dev))

(defn server-can-be-started?
  []
  (or
    (nil? (-> server-dev deref))
    (.isStopped
      ^Server
      (-> server-dev deref :io.pedestal.http/server))))

(defn start-dev []
  (if-not
    (server-can-be-started?)
    (println "start-dev requested but Jetty Server is already running.")
    (reset!
     server-dev
     (http/start
      (http/create-server
       (-> service-map
           (assoc
            ::http/join? false)))))))

(defn start
  ([]
   (start true))
  ([join?]
   (if
     (or
       (nil? (-> server-prod deref))
       (.isStopped
         ^Server
         (-> server-prod deref :io.pedestal.http/server)))
     (reset!
      server-prod
      (http/start
       (http/create-server
        (assoc service-map
               ::http/join? join?)))))))

(defn restart []
  (stop-dev)
  (start-dev))
