(ns todo.ui.views
  "Reagent components. Hiccup — nested vectors — instead of JSX or Razor:
  the markup is a Clojure data structure, so mapping, filtering and
  extracting components is ordinary code, no templating language."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [todo.ui.state :as state]))

(defn- fmt-time [iso-or-inst]
  (.toLocaleString (js/Date. iso-or-inst)
                   js/undefined
                   #js {:dateStyle "medium" :timeStyle "short"}))

;;; auth

(defn auth-page []
  (let [form (r/atom {:mode :login :email "" :password ""})]
    (fn []
      (let [{:keys [mode email password]} @form
            error  (:auth-error @state/db)
            login? (= mode :login)]
        [:div {:class "min-h-screen flex items-center justify-center bg-gradient-to-br from-slate-900 via-slate-800 to-indigo-950 px-4"}
         [:div {:class "w-full max-w-md"}
          [:h1 {:class "text-3xl font-bold text-white text-center mb-2"} "todos"]
          [:p {:class "text-slate-400 text-center mb-8"}
           "a full-stack Clojure demo — Datomic · reitit · malli · reagent"]
          [:form {:class "bg-white rounded-2xl shadow-xl p-8 space-y-4"
                  :on-submit (fn [e]
                               (.preventDefault e)
                               (if login?
                                 (state/login! email password)
                                 (state/register! email password)))}
           [:h2 {:class "text-xl font-semibold text-slate-800"}
            (if login? "Sign in" "Create an account")]
           (when error
             [:div {:class "rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm px-3 py-2"}
              error])
           [:label {:class "block"}
            [:span {:class "text-sm font-medium text-slate-600"} "Email"]
            [:input {:class "mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                     :type "email" :required true :value email
                     :placeholder "you@example.com"
                     :on-change #(swap! form assoc :email (.. % -target -value))}]]
           [:label {:class "block"}
            [:span {:class "text-sm font-medium text-slate-600"} "Password"]
            [:input {:class "mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                     :type "password" :required true :value password
                     :placeholder (if login? "your password" "at least 8 characters")
                     :on-change #(swap! form assoc :password (.. % -target -value))}]]
           [:button {:class "w-full rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-2.5 transition-colors"
                     :type "submit"}
            (if login? "Sign in" "Register")]
           [:p {:class "text-sm text-slate-500 text-center"}
            (if login? "No account yet? " "Already registered? ")
            [:button {:class "text-indigo-600 hover:underline font-medium"
                      :type "button"
                      :on-click (fn [_]
                                  (state/clear-auth-error!)
                                  (swap! form update :mode {:login :register :register :login}))}
             (if login? "Register" "Sign in")]]]]]))))

;;; todos

(defn- history-panel [entries]
  [:div {:class "mt-2 ml-9 rounded-lg bg-slate-50 border border-slate-200 px-3 py-2"}
   [:p {:class "text-xs font-semibold text-slate-400 uppercase tracking-wide mb-1"}
    "every change, straight from the database — no audit table was written"]
   (if (empty? entries)
     [:p {:class "text-sm text-slate-500"} "no history"]
     [:ul {:class "space-y-0.5"}
      (for [[i {:keys [at attribute value op]}] (map-indexed vector entries)]
        ^{:key i}
        [:li {:class "text-sm text-slate-600 font-mono"}
         [:span {:class "text-slate-400"} (fmt-time at)]
         " · " (name attribute) " "
         [:span {:class (if (= op :set) "text-emerald-600" "text-red-500")}
          (if (= op :set) "→" "retracted")]
         " " (pr-str value)])])])

(defn- todo-item [{:keys [id title done] :as todo}]
  (let [history (get-in @state/db [:history id])]
    [:li {:class "group"}
     [:div {:class "flex items-center gap-3 rounded-xl px-3 py-2.5 hover:bg-slate-50 transition-colors"}
      [:button {:class (str "shrink-0 w-6 h-6 rounded-full border-2 flex items-center justify-center transition-colors "
                            (if done
                              "bg-emerald-500 border-emerald-500 text-white"
                              "border-slate-300 hover:border-indigo-400 text-transparent"))
                :title (if done "mark as not done" "mark as done")
                :on-click #(state/toggle-todo! todo)}
       [:svg {:class "w-3.5 h-3.5" :viewBox "0 0 20 20" :fill "currentColor"}
        [:path {:fill-rule "evenodd" :clip-rule "evenodd"
                :d "M16.7 5.3a1 1 0 010 1.4l-8 8a1 1 0 01-1.4 0l-4-4a1 1 0 111.4-1.4L8 12.6l7.3-7.3a1 1 0 011.4 0z"}]]]
      [:span {:class (str "flex-1 text-slate-800 "
                          (when done "line-through text-slate-400"))}
       title]
      [:button {:class (str "shrink-0 text-xs font-medium rounded-md px-2 py-1 transition-colors "
                            (if history
                              "bg-indigo-100 text-indigo-700"
                              "text-slate-400 hover:text-indigo-600 opacity-0 group-hover:opacity-100"))
                :title "show this todo's full history"
                :on-click #(state/toggle-history! id)}
       "history"]
      [:button {:class "shrink-0 text-slate-300 hover:text-red-500 text-lg leading-none px-1 opacity-0 group-hover:opacity-100 transition-all"
                :title "delete"
                :on-click #(state/delete-todo! id)}
       "×"]]
     (when history
       [history-panel history])]))

(defn- todo-list []
  (let [new-title (r/atom "")]
    (fn []
      (let [todos (:todos @state/db)
            open  (remove :done todos)]
        [:div {:class "bg-white rounded-2xl shadow-xl p-6"}
         [:div {:class "flex items-baseline justify-between mb-4"}
          [:h2 {:class "text-lg font-semibold text-slate-800"} "Todos"]
          [:span {:class "text-sm text-slate-400"}
           (str (count open) " open / " (count todos) " total")]]
         [:form {:class "flex gap-2 mb-4"
                 :on-submit (fn [e]
                              (.preventDefault e)
                              (let [title (str/trim @new-title)]
                                (when (seq title)
                                  (state/add-todo! title)
                                  (reset! new-title ""))))}
          [:input {:class "flex-1 rounded-lg border border-slate-300 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                   :placeholder "What needs doing?"
                   :value @new-title
                   :on-change #(reset! new-title (.. % -target -value))}]
          [:button {:class "rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white font-semibold px-4 transition-colors"
                    :type "submit"}
           "Add"]]
         (if (empty? todos)
           [:p {:class "text-slate-400 text-center py-8"} "Nothing yet — add your first todo above."]
           [:ul {:class "divide-y divide-slate-100"}
            (for [todo todos]
              ^{:key (:id todo)} [todo-item todo])])]))))

;;; weather

(defn- weather-card []
  (let [{:keys [city data loading? error]} (:weather @state/db)]
    [:div {:class "bg-white rounded-2xl shadow-xl p-6"}
     [:h2 {:class "text-lg font-semibold text-slate-800 mb-1"} "Weather"]
     [:p {:class "text-xs text-slate-400 mb-4"}
      "live JSON from Open-Meteo, schema-checked at the server boundary"]
     [:div {:class "flex flex-wrap gap-2 mb-4"}
      (for [{:keys [name] :as c} state/cities]
        ^{:key name}
        [:button {:class (str "text-sm rounded-full px-3 py-1 border transition-colors "
                              (if (= name (:name city))
                                "bg-indigo-600 border-indigo-600 text-white"
                                "border-slate-300 text-slate-600 hover:border-indigo-400"))
                  :on-click #(state/load-weather! c)}
         name])]
     (cond
       loading? [:p {:class "text-slate-400"} "Fetching…"]
       error    [:p {:class "text-red-600 text-sm"} error]
       data     [:div
                 [:div {:class "flex items-end gap-3"}
                  [:span {:class "text-5xl font-bold text-slate-800"}
                   (str (js/Math.round (:temperature-c data)) "°C")]
                  [:span {:class "text-slate-500 pb-1"} (:condition data)]]
                 [:dl {:class "mt-3 grid grid-cols-2 gap-2 text-sm"}
                  [:div
                   [:dt {:class "text-slate-400"} "Humidity"]
                   [:dd {:class "text-slate-700 font-medium"} (str (:humidity-pct data) "%")]]
                  [:div
                   [:dt {:class "text-slate-400"} "Wind"]
                   [:dd {:class "text-slate-700 font-medium"} (str (:wind-kmh data) " km/h")]]]
                 [:p {:class "mt-3 text-xs text-slate-400"}
                  (str "observed " (fmt-time (:observed-at data)) ", " (:name city))]]
       :else    [:p {:class "text-slate-400 text-sm"} "Pick a city."])]))

;;; layout

(defn- error-popup []
  (when-let [message (:error-popup @state/db)]
    [:div {:class "fixed inset-x-0 top-4 z-50 px-4 pointer-events-none"}
     [:div {:class "mx-auto max-w-md pointer-events-auto flex items-start gap-3 rounded-xl bg-red-600 text-white shadow-2xl px-4 py-3"}
      [:span {:class "shrink-0 font-semibold"} "Request failed"]
      [:span {:class "flex-1 text-sm text-red-100"} message]
      [:button {:class "shrink-0 text-red-200 hover:text-white text-lg leading-none"
                :title "dismiss"
                :on-click state/dismiss-error!}
       "×"]]]))

(defn- header [user]
  [:header {:class "flex items-center justify-between mb-6"}
   [:div
    [:h1 {:class "text-2xl font-bold text-white"} "todos"]
    [:p {:class "text-slate-400 text-sm"} "Clojure · Datomic · reagent"]]
   [:div {:class "flex items-center gap-3"}
    [:span {:class "text-slate-300 text-sm"} (:email user)]
    [:button {:class "text-sm rounded-lg border border-slate-600 text-slate-300 hover:bg-slate-700 px-3 py-1.5 transition-colors"
              :on-click state/logout!}
     "Sign out"]]])

(defn app []
  (let [user (:user @state/db)]
    (cond
      (= user :loading)
      [:div {:class "min-h-screen flex items-center justify-center bg-slate-900"}
       [:p {:class "text-slate-400"} "Loading…"]]

      (nil? user)
      [auth-page]

      :else
      [:div {:class "min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-indigo-950 px-4 py-8"}
       [error-popup]
       [:div {:class "max-w-5xl mx-auto"}
        [header user]
        [:div {:class "grid gap-6 md:grid-cols-[1fr_20rem] items-start"}
         [todo-list]
         [weather-card]]]])))
