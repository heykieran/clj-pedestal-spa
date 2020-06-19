(ns server.transit-handlers
  (:refer-clojure :exclude [format])
  #?(:cljs (:require-macros server.transit-handlers))
  (:require
    [cognitect.transit :as transit]
    #?(:clj [java-time :as t])
    #?@(:cljs [[goog.string :as gs]
               goog.date.UtcDateTime
               goog.date.Date])))

#?(:clj (set! *warn-on-reflection* true))

(defn write-local-date[val-date]
  "Represent Date in YYYY-MM-DD format."
  #?(:clj
     (t/format val-date)
     :cljs
     (if val-date
       (.toIsoString val-date true false))))

(defn read-local-date[val-date-str]
  "Read Date in YYYY-MM-DD format."
  #?(:clj
     (t/local-date
       (t/formatter
         :iso-local-date
         {:resolver-style :smart})
       val-date-str)
     :cljs
     (let
       [[mtch y m d] (re-find #"^(\d{4})-(\d{2})-(\d{2})$" val-date-str)]
       (if mtch
         (goog.date.Date. (long y) (dec (long m)) (long d))))))

#?(:cljs
   (defn read-float
     [float-str]
     (js/parseFloat float-str)))

(def writers
  {#?(:clj java.time.LocalDate, :cljs goog.date.Date)
   (transit/write-handler
     (constantly "local-date")
     write-local-date)})

(def readers
  ; 1 argument arity version must be explicitly used for clojurescript
  (merge
    {"local-date"
     (transit/read-handler read-local-date)}
    #?(:cljs
       {"f"
        (transit/read-handler read-float)})))
