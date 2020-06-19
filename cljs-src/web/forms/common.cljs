(ns web.forms.common
  (:require [reagent.core :as reagent]
            [re-frame.core :as rf]
            [cljsjs.semantic-ui-react :as semantic]
            [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                               oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
            [goog.object]))


;; --- Semantic UI ---
(def semantic-ui js/semanticUIReact)

(defn component
      "Get a component from sematic-ui-react:

        (component \"Button\")
        (component \"Menu\" \"Item\")"
      [k & ks]
      (if (seq ks)
        (apply goog.object/getValueByKeys semantic-ui k ks)
        (goog.object/get semantic-ui k)))

(def breadcrumb         (component "Breadcrumb"))
(def breadcrumb-divider (component "Breadcrumb" "Divider"))
(def breadcrumb-section (component "Breadcrumb" "Section"))
(def button             (component "Button"))
(def container          (component "Container"))
(def checkbox           (component "Checkbox"))
(def divider            (component "Divider"))
(def dropdown           (component "Dropdown"))
(def dropdown-menu      (component "Dropdown" "Menu"))
(def dropdown-item      (component "Dropdown" "Item"))
(def form               (component "Form"))
(def form-group         (component "Form" "Group"))
(def form-field         (component "Form" "Field"))
(def form-input         (component "Form" "Input"))
(def form-dropdown      (component "Form" "Dropdown"))
(def grid               (component "Grid"))
(def grid-row           (component "Grid" "Row"))
(def grid-column        (component "Grid" "Column"))
(def header             (component "Header"))
(def header-content     (component "Header" "Content"))
(def icon               (component "Icon"))
(def icon-group         (component "Icon" "Group"))
(def image              (component "Image"))
(def image-group        (component "Image" "Group"))
(def input              (component "Input"))
(def label              (component "Label"))
(def list-sem           (component "List"))
(def list-content       (component "List" "Content"))
(def list-description   (component "List" "Description"))
(def list-header        (component "List" "Header"))
(def list-icon          (component "List" "Icon"))
(def list-item          (component "List" "Item"))
(def list-list          (component "List" "List"))
(def loader             (component "Loader"))
(def menu               (component "Menu"))
(def menu-item          (component "Menu" "Item"))
(def menu-menu          (component "Menu" "Menu"))
(def message            (component "Message"))
(def message-header     (component "Message" "Header"))
(def message-content    (component "Message" "Content"))
(def modal              (component "Modal"))
(def modal-header       (component "Modal" "Header"))
(def modal-description  (component "Modal" "Description"))
(def modal-content      (component "Modal" "Content"))
(def modal-actions      (component "Modal" "Actions"))
(def pagination         (component "Pagination"))
(def pagination-item    (component "Pagination" "Item"))
(def popup              (component "Popup"))
(def portal             (component "Portal"))
(def segment            (component "Segment"))
(def segment-group      (component "Segment" "Group"))
(def sidebar            (component "Sidebar"))
(def sidebar-pushable   (component "Sidebar" "Pushable"))
(def sidebar-pusher     (component "Sidebar" "Pusher"))
(def tab                (component "Tab"))
(def tab-pane           (component "Tab" "Pane"))
(def table              (component "Table"))
(def table-body         (component "Table" "Body"))
(def table-cell         (component "Table" "Cell"))
(def table-footer       (component "Table" "Footer"))
(def table-header       (component "Table" "Header"))
(def table-header-cell  (component "Table" "HeaderCell"))
(def table-row          (component "Table" "Row"))


