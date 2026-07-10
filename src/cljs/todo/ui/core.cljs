(ns todo.ui.core
  (:require [reagent.dom.client :as rdc]
            [todo.ui.state :as state]
            [todo.ui.views :as views]))

(defonce root
  (delay (rdc/create-root (js/document.getElementById "app"))))

(defn ^:dev/after-load render []
  (rdc/render @root [views/app]))

(defn init []
  (state/load-user!)
  (render))
