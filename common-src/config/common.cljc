(ns config.common)

(def host
  #?(:clj (or (System/getenv "ALLOC_HOST_NAME") "penguin.linux.test")
     :cljs (.-hostname js/location)))

(def ^:const google-client-id "486988131533-luvn04a1l3cbit82ihgrq4ioobq33kt3.apps.googleusercontent.com")


