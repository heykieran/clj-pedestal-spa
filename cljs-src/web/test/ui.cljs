(ns web.test.ui
  (:require
    [re-frame.core :as rf]
    [web.forms.common :as sem]
    [web.test.es]))

(defn public-ui[]
  (fn[]
    [:<>
     [:> sem/segment "Public"]
     [:> sem/message
      (pr-str @(rf/subscribe [:test/get-pass-fail]))]]))

(defn user-ui[]
  (fn[]
    [:<>
     [:> sem/segment "User"]
     [:> sem/message
      (pr-str @(rf/subscribe [:test/get-pass-fail]))]]))

(defn admin-ui[]
  (fn[]
    [:<>
     [:> sem/segment "Admin"]
     [:> sem/message
      (pr-str @(rf/subscribe [:test/get-pass-fail]))]]))