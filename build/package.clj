(ns package
  (:require
    [clojure.core :as core]
    [clojure.tools.deps.alpha.reader :as deps-reader]
    [badigeon.bundle :refer [bundle make-out-path]]
    [badigeon.compile :as compile]
    [badigeon.classpath :as classpath]))

(defn
  -main []
  (let
    [out-path (make-out-path 'app nil)
     deps-map (deps-reader/slurp-deps "deps.edn")]
    (bundle
      out-path
      {:deps-map deps-map
       :aliases [:main]
       :libs-path "lib"})
    (compile/compile
      'main.core
      {:compile-path
       "target/classes"
       :classpath
       (classpath/make-classpath
         {:deps-map deps-map
          :aliases [:main]})})))
