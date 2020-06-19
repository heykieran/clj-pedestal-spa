(ns server.utils
  (:require
    [cognitect.transit :as transit]
    [clojure.string]
    [server.transit-handlers :as tr-handlers])
  (:import
    [java.io ByteArrayInputStream ByteArrayOutputStream]
    [java.nio.charset StandardCharsets]))

(defn value->json-string
  [value]
  (let
    [out (ByteArrayOutputStream. 4096)
     writer
     (transit/writer
       out
       :json
       #_{:handlers
          {java.time.LocalDate local-date-write-handler}})]
    (transit/write writer value)
    (.toString out)))

(defn value->transit-string
  [value]
  (let
    [out (ByteArrayOutputStream. 4096)
     writer
     (transit/writer
       out
       :json
       {:handlers tr-handlers/writers})]
    (transit/write writer value)
    (.toString out)))

(defn json-string->value
  [json-string]
  (let
    [in (-> json-string
            (.getBytes StandardCharsets/UTF_8)
            (java.io.ByteArrayInputStream.))]
    (transit/read
      (transit/reader in :json))))

(defn transit-string->value
  [json-string]
  (let
    [in (-> json-string
            (.getBytes StandardCharsets/UTF_8)
            (java.io.ByteArrayInputStream.))]
    (transit/read
      (transit/reader
        in
        :json
        {:handlers tr-handlers/readers}))))

(defn unsafe-extract-account-kw
  [kw]
  (if (and (keyword? kw) (not (empty? (name kw))))
    (if-let [a-kw (keyword "tmp" (name kw))]
      (if-let [i (clojure.string/last-index-of (.-sym a-kw) "/")]
        (keyword
          (if i
            (subs (str (.-sym a-kw)) (-> i inc))
            (subs (str (.-sym a-kw)) 1)))
        (keyword (.-sym a-kw)))
      kw)))



