(ns todo.ui.state
  "All client state lives in one reagent atom. Components deref it and
  re-render when the parts they read change; these functions are the only
  code that mutates it. Same unidirectional idea as Redux, in ~100 lines
  and no library beyond reagent itself."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [todo.ui.api :as api]
            [malli.core :as m]
            [todo.schema :as schema]))

(defonce db
  (r/atom {:user        :loading   ; :loading | nil (logged out) | {:id .. :email ..}
           :todos       []
           :auth-error  nil
           :error-popup nil        ; message string shown as a dismissable popup
           :history     {}         ; todo-id -> audit entries, for open panels
           :weather     {:city nil :data nil :loading? false :error nil}}))

(comment
  (swap! db update :todos conj
         {:id #uuid "8650E15D-EA79-42BF-BB76-25816681E2F9" :title "Hellow world" :done false
          :created-at #inst "2026-06-04"})

  @db

  ())

(def cities
  [{:name "Cape Town"    :lat -33.93 :lon 18.42}
   {:name "Johannesburg" :lat -26.20 :lon 28.05}
   {:name "London"       :lat 51.51  :lon -0.13}
   {:name "Berlin"       :lat 52.52  :lon 13.41}
   {:name "New York"     :lat 40.71  :lon -74.01}])

(defn- error-of [body]
  (or (:error body)
      ;; coercion failures arrive as data, e.g. {:title ["should be at ..."]},
      ;; so "humanizing" further is just string formatting
      (some->> (:humanized body)
               (map (fn [[field messages]]
                      (str (name field) ": " (str/join ", " messages))))
               (str/join "; ")
               not-empty)
      "something went wrong"))

(defn dismiss-error! []
  (swap! db assoc :error-popup nil))

(defn- show-error!
  "Surface a failed response as a popup; auto-dismisses after a few seconds
  unless a newer error has replaced it in the meantime."
  [body]
  (let [message (error-of body)]
    (swap! db assoc :error-popup message)
    (js/setTimeout #(swap! db (fn [s]
                                (cond-> s
                                  (= (:error-popup s) message) (assoc :error-popup nil))))
                   6000)))

;;; todos

(defn load-todos! []
  (-> (api/GET "/api/todos")
      (.then (fn [{:keys [ok? body]}]
               (when ok? (swap! db assoc :todos body))))))

(defn add-todo! [title]
  (let [new-todo {:title title}]
    (js/console.log "Errors: " (:errors (m/explain schema/NewTodo new-todo)))
    (-> (api/POST "/api/todos" new-todo)
        (.then (fn [{:keys [ok? body]}]
                 (if ok?
                   (swap! db update :todos conj body)
                   (show-error! body)))))))

(defn- replace-todo [todos updated]
  (mapv #(if (= (:id %) (:id updated)) updated %) todos))

(defn toggle-todo! [{:keys [id done]}]
  (-> (api/PATCH (str "/api/todos/" id) {:done (not done)})
      (.then (fn [{:keys [ok? body]}]
               (when ok?
                 (swap! db update :todos replace-todo body)
                 ;; refresh the audit panel if it's open for this todo
                 (when (get-in @db [:history id])
                   (-> (api/GET (str "/api/todos/" id "/history"))
                       (.then (fn [{:keys [ok? body]}]
                                (when ok? (swap! db assoc-in [:history id] body)))))))))))

(defn delete-todo! [id]
  (-> (api/DELETE (str "/api/todos/" id))
      (.then (fn [{:keys [ok?]}]
               (when ok?
                 (swap! db (fn [s]
                             (-> s
                                 (update :todos (fn [ts] (vec (remove #(= (:id %) id) ts))))
                                 (update :history dissoc id)))))))))

(defn toggle-history! [id]
  (if (get-in @db [:history id])
    (swap! db update :history dissoc id)
    (-> (api/GET (str "/api/todos/" id "/history"))
        (.then (fn [{:keys [ok? body]}]
                 (when ok? (swap! db assoc-in [:history id] body)))))))

;;; auth

(defn load-user!
  "Called once at boot: is there a live session cookie?"
  []
  (-> (api/GET "/api/auth/me")
      (.then (fn [{:keys [ok? body]}]
               (swap! db assoc :user (when ok? (:user body)))
               (when ok? (load-todos!))))))

(defn- enter! [{:keys [ok? body]}]
  (if ok?
    (do (swap! db assoc :user (:user body) :auth-error nil)
        (load-todos!))
    (swap! db assoc :auth-error (error-of body))))

(defn login! [email password]
  (-> (api/POST "/api/auth/login" {:email email :password password})
      (.then enter!)))

(defn register! [email password]
  (-> (api/POST "/api/auth/register" {:email email :password password})
      (.then enter!)))

(defn logout! []
  (-> (api/POST "/api/auth/logout")
      (.then (fn [_]
               (reset! db {:user nil :todos [] :auth-error nil :error-popup nil :history {}
                           :weather {:city nil :data nil :loading? false :error nil}})))))

(defn clear-auth-error! []
  (swap! db assoc :auth-error nil))

;;; weather

(defn load-weather! [{:keys [lat lon] :as city}]
  (swap! db assoc :weather {:city city :data nil :loading? true :error nil})
  (-> (api/GET (str "/api/weather?lat=" lat "&lon=" lon))
      (.then (fn [{:keys [ok? body]}]
               (swap! db assoc :weather
                      {:city     city
                       :data     (when ok? body)
                       :loading? false
                       :error    (when-not ok? (error-of body))})))))
