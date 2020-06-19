(ns utils.utils
  (:require [java-time :as time]))

(defn possible-string-as-keyword
  [id-or-string-with-colon]
  (if
    (keyword? id-or-string-with-colon)
    id-or-string-with-colon
    (if (and
          (string? id-or-string-with-colon)
          (clojure.string/starts-with? id-or-string-with-colon ":"))
      (keyword (subs id-or-string-with-colon 1))
      (keyword id-or-string-with-colon))))

(defn possible-keyword-as-string
  [id-or-string-with-colon]
  (if
    (keyword? id-or-string-with-colon)
    (str ":" (name id-or-string-with-colon))
    (if (and
          (string? id-or-string-with-colon)
          (clojure.string/starts-with? id-or-string-with-colon ":"))
      id-or-string-with-colon
      (str ":" (str id-or-string-with-colon)))))

(defn get-local-timestamp-with-offset
  []
  (time/format
    :iso-offset-date-time
    (time/zoned-date-time
      (time/local-date-time)
      (time/zone-id))))