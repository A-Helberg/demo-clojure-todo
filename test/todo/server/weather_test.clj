(ns todo.server.weather-test
  "No network here: these pin down the boundary between us and Open-Meteo —
  what shape we accept from them, and what we turn it into."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [todo.schema :as schema]
            [todo.server.weather :as weather]))

(def open-meteo-payload
  {:latitude -33.9
   :longitude 18.4
   :timezone "Africa/Johannesburg"
   :current {:time "2026-07-03T14:00"
             :interval 900
             :temperature_2m 17.3
             :relative_humidity_2m 68
             :weather_code 61
             :wind_speed_10m 22.5}})

(deftest provider-schema
  (testing "the real payload shape validates (extra keys are fine)"
    (is (m/validate schema/OpenMeteoResponse open-meteo-payload)))
  (testing "a missing or renamed field is caught at the boundary"
    (is (not (m/validate schema/OpenMeteoResponse
                         (update open-meteo-payload :current dissoc :temperature_2m))))
    (is (not (m/validate schema/OpenMeteoResponse
                         (assoc-in open-meteo-payload [:current :temperature_2m] "17.3"))))))

(deftest shaping
  (let [result (weather/shape open-meteo-payload)]
    (is (= {:temperature-c 17.3
            :humidity-pct  68
            :wind-kmh      22.5
            :condition     "Light rain"
            :observed-at   "2026-07-03T14:00"}
           result))
    (testing "what we produce matches what we promise the frontend"
      (is (m/validate schema/Weather result)))))

(deftest unknown-wmo-code
  (is (= "Unknown"
         (:condition (weather/shape (assoc-in open-meteo-payload
                                              [:current :weather_code] 42))))))
