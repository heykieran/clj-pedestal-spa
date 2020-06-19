(ns server.fe-handler
  (:require
    [compojure.core :refer :all]
    [compojure.route :as route]
    [ring.middleware.reload :refer [wrap-reload]]
    [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
    [ring.middleware.session :as ring-session]
    [ring.middleware.session.cookie :refer [cookie-store]]
    [ring.middleware.cors :refer [wrap-cors]]
    [compojure.api.sweet :as api]
    [clojure.string :as str]
    [hiccup.core :refer [html]]
    [config.server :as server-config]))

(import [java.io ByteArrayInputStream ByteArrayOutputStream])

(def default-content-security-policy
  (str "default-src 'self'; "
       "img-src 'self' " server-config/my-hostname ":*; "
       "connect-src 'self' ws://localhost:* wss://localhost:* https://localhost:* " "wss://" server-config/host ":* " server-config/my-hostname ":*; "
       "script-src 'self' " server-config/my-hostname ":*" " https://apis.google.com 'unsafe-eval' 'unsafe-inline';"
       "script-src-elem 'self' " server-config/my-hostname ":*" " https://apis.google.com https://cdn.jsdelivr.net https://code.jquery.com 'unsafe-inline';"
       "font-src 'self' data: https://fonts.gstatic.com https://cdn.jsdelivr.net;"
       "child-src 'self' https://accounts.google.com; "
       "style-src 'self' https://accounts.google.com https://apis.google.com https://fonts.googleapis.com https://cdn.jsdelivr.net/npm/semantic-ui@2.4.2/dist/semantic.min.css 'unsafe-inline';"))

(defn path-starts-with?
  [path target]
  (letfn
    [(strip-slashes[path]
       (clojure.string/replace
         path
         #"(^/)?(.*)??(/$)?" "$2"))]
    (loop [c-p (strip-slashes path)
           c-t (strip-slashes target)
           result true
           r-c 0]
      (if (> r-c 100)
        (throw
          (Exception.
            (str "Recursion failure in path-starts-with? "
                 "path:" (pr-str path) " "
                 "target: " (pr-str target))))
        (if (or (not result) (empty? c-t))
          result
          (if (and (empty? c-p) (empty? c-t))
            result
            (recur
              (rest c-p)
              (rest c-t)
              (and result (= (first c-p) (first c-t)))
              (inc r-c))))))))

;; Ring/Compojure Helper Functions

(defn ignore-trailing-slash
  "Modifies the request uri before calling the handler.
  Removes a single trailing slash from the end of the uri if present.
  Useful for handling optional trailing slashes until Compojure's route matching syntax supports regex.
  Adapted from http://stackoverflow.com/questions/8380468/compojure-regex-for-matching-a-trailing-slash"
  [handler]
  (fn [request]
    (let [uri (:uri request)]
      (handler
        (assoc
          request
          :uri
          (if
            (and
              (not (= "/" uri))
              (.endsWith uri "/"))
            (subs uri 0 (dec (count uri)))
            uri))))))

(defn response-in-html [response unwrapped-body]
  (->
    response
    (update
      :headers
      merge
      {"Content-Type" "text/html"
       "X-TEST-KJO-HDR" "HELLO THERE"})
    (assoc
      :body
      (str "<html><head><title>Blank FE Handler Title</title>"
           "<link rel=\"stylesheet\" type=\"text/css\" href=\"/css/style.css\">"
           "</head><body>"
           unwrapped-body
           "</body></html>"))))

(defn figwheel-response
  [figwheel-js-path
   launch-function]
  (html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name    "google-signin-client_id"
              :content server-config/google-client-id}]
      [:base {:href "/"}]
      [:title "ReFrame Application (FE Server)"]
      [:link
       {:rel "stylesheet"
        :href "https://cdn.jsdelivr.net/npm/semantic-ui@2.4.2/dist/semantic.min.css"}]
      [:link
       {:rel "stylesheet"
        :href "css/app-style.css"}]]
     [:body
      [:div#app]
      [:script
       {:src figwheel-js-path}]
      [:script launch-function]
      [:script {:src "https://apis.google.com/js/platform.js" :async true :defer true}]
      [:script {:src "mainapp.js" :async true :defer true}]]]))

(defn wrap-response-in-html
  [handler path]
  (fn [request]
    (let [response (handler request)
          unwrapped-body (:body response)]
      (if (and (path-starts-with?
                 (:uri request)
                 path)
               unwrapped-body)
        (response-in-html response unwrapped-body)
        response))))

;; End Ring/Compojure Helper Functions

(defn set-fe-server-headers-handler
  [handler]
  (fn[request]
    (let
      [response (handler request)]
      (update
        response
        :headers
        merge
        {"X-ALLOC-SERVER-IDENTIFIER" "frontend-server"
         "Access-Control-Allow-Credentials" "true"
         "Content-Security-Policy" default-content-security-policy}))))

(def api-app
  (api/api
    (api/undocumented
      (route/resources "/")
      (GET "/hello" [] "Greetings from the HELLO route")
      (GET "/r/home" []
           (figwheel-response
             "cljs-out/dev-main.js"
             "web.reframe.init();"))
      (route/not-found "Route not found by fe-handler."))))

(def wrapped-app
  (-> api-app
      (wrap-cors
        :access-control-allow-origin [#".*"]
        :access-control-allow-methods [:get :put :post])
      (set-fe-server-headers-handler)
      (wrap-defaults
        (assoc site-defaults :session false))
      (ignore-trailing-slash)
      (wrap-response-in-html "hello")))

(def reloadable-app
  (wrap-reload
    #'wrapped-app
    {:dirs ["src"]}))

(def fe-handler
  reloadable-app)

