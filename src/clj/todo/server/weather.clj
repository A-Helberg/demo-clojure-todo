(ns todo.server.weather
  "Current conditions from Open-Meteo (free JSON API, no key required).
  The interesting part is the boundary discipline: the provider's JSON is
  malli-validated before anything else touches it. The HTTP call itself is
  plain interop with the JDK's java.net.http — no wrapper library needed."
  (:require [jsonista.core :as json]
            [malli.core :as m]
            [malli.error :as me]
            [todo.schema :as schema])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)))

(defonce ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 5))
             (.build))))

(def ^:private base-url "https://api.open-meteo.com/v1/forecast")

;; WMO weather interpretation codes, as documented by Open-Meteo.
(def ^:private wmo-codes
  {0 "Clear sky" 1 "Mostly clear" 2 "Partly cloudy" 3 "Overcast"
   45 "Fog" 48 "Rime fog"
   51 "Light drizzle" 53 "Drizzle" 55 "Heavy drizzle"
   56 "Freezing drizzle" 57 "Freezing drizzle"
   61 "Light rain" 63 "Rain" 65 "Heavy rain"
   66 "Freezing rain" 67 "Freezing rain"
   71 "Light snow" 73 "Snow" 75 "Heavy snow" 77 "Snow grains"
   80 "Light showers" 81 "Showers" 82 "Violent showers"
   85 "Snow showers" 86 "Heavy snow showers"
   95 "Thunderstorm" 96 "Thunderstorm with hail" 99 "Thunderstorm with heavy hail"})

(defn- fetch-json [lat lon]
  (let [url      (str base-url
                      "?latitude=" lat "&longitude=" lon
                      "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                      "&timezone=auto")
        request  (-> (HttpRequest/newBuilder)
                     (.uri (URI/create url))
                     (.timeout (Duration/ofSeconds 10))
                     (.GET)
                     (.build))
        response (.send @client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info "weather provider returned an error"
                      {:type ::upstream :status (.statusCode response)})))
    (json/read-value (.body response) json/keyword-keys-object-mapper)))

(defn shape
  "Validated Open-Meteo payload -> our API's weather shape (schema/Weather)."
  [payload]
  (let [{:keys [time temperature_2m relative_humidity_2m weather_code wind_speed_10m]}
        (:current payload)]
    {:temperature-c temperature_2m
     :humidity-pct  relative_humidity_2m
     :wind-kmh      wind_speed_10m
     :condition     (get wmo-codes (long weather_code) "Unknown")
     :observed-at   time}))

(defn current
  "Current conditions at lat/lon. Throws ex-info {:type ::upstream ..} when
  the provider is down or its JSON stops matching what we depend on."
  [lat lon]
  (let [payload (fetch-json lat lon)]
    (when-not (m/validate schema/OpenMeteoResponse payload)
      (throw (ex-info "weather provider sent an unexpected shape"
                      {:type    ::upstream
                       :details (me/humanize (m/explain schema/OpenMeteoResponse payload))})))
    (shape payload)))
