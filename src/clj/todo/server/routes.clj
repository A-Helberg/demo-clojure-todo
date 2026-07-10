(ns todo.server.routes
  "The whole API surface as one data structure. Routes are vectors and maps
  — you can print them, diff them, or generate an OpenAPI spec from them.
  :parameters/:responses entries are malli schemas; reitit coerces and
  validates against them before/after each handler runs, and the same
  schemas generate /api/openapi.json (browse it at /api-docs)."
  (:require [muuntaja.core :as m]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.openapi :as openapi]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger-ui :as swagger-ui]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [todo.server.auth :as auth]
            [todo.server.handlers :as h]
            [todo.schema :as schema]))

(defn api-routes [conn]
  ["/api"
   ["/openapi.json"
    {:get {:no-doc  true
           :openapi {:info {:title       "todos API"
                            :description (str "A full-stack Clojure demo. Auth is an http-only "
                                              "session cookie set by register/login; the /todos "
                                              "and /me endpoints require it. Endpoints speak EDN "
                                              "or JSON via content negotiation.")
                            :version     "1.0.0"}
                     :components {:securitySchemes
                                  {"session" {:type "apiKey" :in "cookie" :name "todo-session"}}}}
           :handler (openapi/create-openapi-handler)}}]

   ["/auth" {:tags #{"auth"}}
    ["/register" {:post {:summary    "Create an account (and log in)"
                         :parameters {:body schema/RegisterRequest}
                         :responses  {201 {:body [:map [:user schema/User]]}}
                         :handler    (h/register conn)}}]
    ["/login"    {:post {:summary    "Log in; sets the session cookie"
                         :parameters {:body schema/LoginRequest}
                         :responses  {200 {:body [:map [:user schema/User]]}}
                         :handler    (h/login conn)}}]
    ["/logout"   {:post {:summary "Clear the session"
                         :handler h/logout}}]
    ["/me"       {:middleware [auth/wrap-require-user]
                  :get {:summary   "The currently logged-in user"
                        :responses {200 {:body [:map [:user schema/User]]}}
                        :handler   (h/me conn)}}]]

   ["/todos" {:tags       #{"todos"}
              :middleware [auth/wrap-require-user]}
    ["" {:get  {:summary   "All of your todos"
                :responses {200 {:body schema/Todos}}
                :handler   (h/list-todos conn)}
         :post {:summary    "Create a todo"
                :parameters {:body schema/NewTodo}
                :responses  {201 {:body schema/Todo}}
                :handler    (h/create-todo conn)}}]
    ["/:id" {:parameters {:path [:map [:id :uuid]]}}
     ["" {:patch  {:summary    "Retitle a todo and/or set done"
                   :parameters {:body schema/TodoPatch}
                   :responses  {200 {:body schema/Todo}}
                   :handler    (h/patch-todo conn)}
          :delete {:summary "Delete a todo"
                   :handler (h/delete-todo conn)}}]
     ["/history" {:get {:summary   "Every change ever made to a todo (Datomic history)"
                        :responses {200 {:body [:vector schema/HistoryEntry]}}
                        :handler   (h/todo-history conn)}}]]]

   ["/weather" {:tags #{"weather"}
                :get  {:summary    "Current conditions via Open-Meteo"
                       :parameters {:query schema/WeatherQuery}
                       :responses  {200 {:body schema/Weather}}
                       :handler    (h/get-weather)}}]])

(defn app
  "The complete ring handler: API router + swagger-ui + static files + session."
  [{:keys [conn session-secret]}]
  (-> (ring/ring-handler
       (ring/router
        (api-routes conn)
        {:data {:coercion   (malli-coercion/create {:error-keys #{:humanized}})
                :muuntaja   m/instance
                :middleware [parameters/parameters-middleware
                             muuntaja/format-middleware
                             exception/exception-middleware
                             coercion/coerce-exceptions-middleware
                             coercion/coerce-request-middleware
                             coercion/coerce-response-middleware]}})
       (ring/routes
        (swagger-ui/create-swagger-ui-handler {:path   "/api-docs"
                                               :url    "/api/openapi.json"
                                               :config {:validatorUrl nil}})
        ;; Serves resources/public — index.html, compiled JS, compiled CSS.
        (ring/create-resource-handler {:path "/"})
        (ring/create-default-handler
         {:not-found (constantly {:status  404
                                  :headers {"Content-Type" "application/json"}
                                  :body    "{\"error\":\"not found\"}"})})))
      (wrap-session {:store        (cookie-store {:key (auth/session-store-key session-secret)})
                     :cookie-name  "todo-session"
                     :cookie-attrs {:http-only true :same-site :lax}})))
