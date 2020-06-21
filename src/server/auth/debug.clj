(ns server.auth.debug
  (:require
    [buddy.core.keys :as keys]
    [java-time :as time]
    [buddy.sign.jwt :as jwt])
  (:import
    (java.security KeyPairGenerator SecureRandom)))

; a debug-local-jwt is a map that looks like this:
;
;  {:kty "EC",
;   :crv "P-256",
;   :x "xvalue",
;   :y "yvalue",
;   :d "dvalue"})
;
; One can be generated using generate-ec256-debug-keypair-as-jwk
; (see below)

(defn generate-ec256-debug-keypair
  []
  (let
    [keypair-generator
     (KeyPairGenerator/getInstance "EC")]
    (.initialize
      keypair-generator
      256
      (SecureRandom/getInstanceStrong))
    (.genKeyPair keypair-generator)))

(defn generate-ec256-debug-keypair-as-jwk
  []
  (let [pair (generate-ec256-debug-keypair)]
    (keys/jwk
      (.getPrivate pair)
      (.getPublic pair))))

(defn sign-using-debug-key
  [jwt-key payload-map & {:keys [ttl] :or {ttl 3600}}]
  (jwt/sign
    (merge
      payload-map
      {:iss "local"
       :iat (time/instant)
       :aud "local"
       :exp (time/instant
              (time/plus (time/instant) (time/seconds ttl)))})
    (keys/jwk->private-key jwt-key)
    {:alg :es256
     :header {:kid "local"}}))

(defn unsign-using-debug-key
  [jwt-key payload & {:keys [leeway] :or {leeway 0}}]
  (jwt/unsign
    payload
    (keys/jwk->public-key jwt-key)
    {:alg :es256
     :now (time/minus (time/instant) (time/minutes leeway))}))

(defn debug-local-jwt-public
  [jwt-key]
  (merge
    {:kid "local" :use "sig" :alg "ES256"}
    (-> jwt-key
        (keys/jwk->public-key)
        (keys/public-key->jwk))))
