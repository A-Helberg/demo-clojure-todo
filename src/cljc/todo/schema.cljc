(ns todo.schema
  "Malli schemas for every edge of the system: requests in, responses out,
  and third-party JSON. Data that hasn't passed one of these never reaches
  business logic — the moral equivalent of model validation + DTOs in
  ASP.NET, except the schemas are plain data you can inspect and test.")

(def Email
  [:re {:error/message "must be a valid email address"}
   #"^[^@\s]+@[^@\s]+\.[^@\s]+$"])

(def Password
  [:string {:min 8 :max 128 :error/message "password must be 8-128 characters"}])

(def RegisterRequest
  [:map
   [:email Email]
   [:password Password]])

(def LoginRequest
  [:map
   [:email Email]
   [:password :string]])

(def User
  [:map
   [:id :uuid]
   [:email :string]])

(def NewTodo
  [:map
   [:title [:string {:min 1 :max 200}]]])

(def TodoPatch
  [:map
   [:title {:optional true} [:string {:min 1 :max 200}]]
   [:done {:optional true} :boolean]])

(def Todo
  [:map
   [:id :uuid]
   [:title :string]
   [:done :boolean]
   [:created-at inst?]])

(def Todos [:vector Todo])

;; One row of a todo's audit trail, straight out of Datomic's history index.
(def HistoryEntry
  [:map
   [:at inst?]
   [:attribute :keyword]
   [:value :any]
   [:op [:enum :set :retract]]])

(def WeatherQuery
  [:map
   [:lat [:double {:min -90.0 :max 90.0}]]
   [:lon [:double {:min -180.0 :max 180.0}]]])

;; The subset of Open-Meteo's response this app depends on. Their JSON is
;; validated against this on arrival, so a change on their side surfaces as
;; an explicit 502 at the boundary — not a NullReferenceException three
;; layers deep.
(def OpenMeteoResponse
  [:map
   [:current
    [:map
     [:time :string]
     [:temperature_2m number?]
     [:relative_humidity_2m number?]
     [:weather_code number?]
     [:wind_speed_10m number?]]]])

(def Weather
  [:map
   [:temperature-c number?]
   [:humidity-pct number?]
   [:wind-kmh number?]
   [:condition :string]
   [:observed-at :string]])
