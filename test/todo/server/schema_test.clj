(ns todo.server.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [todo.schema :as schema]))

(deftest register-request
  (testing "accepts a well-formed registration"
    (is (m/validate schema/RegisterRequest
                    {:email "ada@example.com" :password "correct-horse"})))
  (testing "rejects bad emails and short passwords"
    (is (not (m/validate schema/RegisterRequest
                         {:email "not-an-email" :password "correct-horse"})))
    (is (not (m/validate schema/RegisterRequest
                         {:email "ada@example.com" :password "short"}))))
  (testing "errors humanize into something you could show a user"
    (is (= {:email ["must be a valid email address"]}
           (me/humanize
            (m/explain schema/RegisterRequest
                       {:email "nope" :password "correct-horse"}))))))

(deftest todo-patch
  (is (m/validate schema/TodoPatch {}))
  (is (m/validate schema/TodoPatch {:done true}))
  (is (m/validate schema/TodoPatch {:title "new title" :done false}))
  (is (not (m/validate schema/TodoPatch {:title ""})))
  (is (not (m/validate schema/TodoPatch {:done "yes"}))))

(deftest weather-query
  (is (m/validate schema/WeatherQuery {:lat -33.93 :lon 18.42}))
  (is (not (m/validate schema/WeatherQuery {:lat 91.0 :lon 0.0})))
  (is (not (m/validate schema/WeatherQuery {:lat 0.0 :lon 181.0}))))
