(ns todo.server.api-test
  "Full-stack API tests against the real ring handler and a real (in-memory)
  Datomic — no mocks, no running server, no port. A request is a map in and
  a response is a map out, so 'integration test' costs the same as a unit test.

  These speak EDN, exactly like the ClojureScript frontend does: note the
  assertions below on keywords, uuids and booleans arriving as themselves —
  no string round-trip. The last test shows the same handler serving JSON
  to a client that asks for it."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [ring.mock.request :as mock]
            [todo.server.db :as db]
            [todo.server.routes :as routes])
  (:import (java.util Date UUID)))

(def ^:dynamic *app* nil)

(use-fixtures :each
  (fn [f]
    (let [conn (db/connect! {:storage-dir :mem
                             :db-name     (str "test-" (random-uuid))})]
      (binding [*app* (routes/app {:conn           conn
                                   :session-secret "test-secret-0123456789"})]
        (f)))))

(defn- call
  [method uri & {:keys [body cookie accept]}]
  (*app* (cond-> (mock/request method uri)
           true   (mock/header "accept" (or accept "application/edn"))
           body   (-> (mock/header "content-type" "application/edn")
                      (mock/body (pr-str body)))
           cookie (mock/header "cookie" cookie))))

(defn- edn-body [response]
  (some-> (:body response) slurp edn/read-string))

(defn- session-cookie
  "The 'todo-session=...' pair from a response's Set-Cookie header."
  [response]
  (some->> (get-in response [:headers "Set-Cookie"])
           first
           (re-find #"^[^;]+")))

(defn- register! [email]
  (session-cookie
   (call :post "/api/auth/register" :body {:email email :password "correct-horse"})))

(deftest registration-and-login
  (testing "register: 201, user in body, session cookie set"
    (let [response (call :post "/api/auth/register"
                         :body {:email "ada@example.com" :password "correct-horse"})
          user     (:user (edn-body response))]
      (is (= 201 (:status response)))
      (is (= "ada@example.com" (:email user)))
      (is (uuid? (:id user)) "EDN keeps the id a uuid across the wire")
      (is (some? (session-cookie response)))))

  (testing "duplicate email -> 409"
    (register! "grace@example.com")
    (is (= 409 (:status (call :post "/api/auth/register"
                              :body {:email "grace@example.com" :password "correct-horse"})))))

  (testing "malformed input -> 400 before any handler runs"
    (is (= 400 (:status (call :post "/api/auth/register"
                              :body {:email "not-an-email" :password "correct-horse"}))))
    (is (= 400 (:status (call :post "/api/auth/register"
                              :body {:email "ada@example.com" :password "short"})))))

  (testing "login round-trip"
    (register! "alan@example.com")
    (is (= 401 (:status (call :post "/api/auth/login"
                              :body {:email "alan@example.com" :password "wrong-password"}))))
    (let [response (call :post "/api/auth/login"
                         :body {:email "alan@example.com" :password "correct-horse"})]
      (is (= 200 (:status response)))
      (let [cookie (session-cookie response)
            me     (call :get "/api/auth/me" :cookie cookie)]
        (is (= 200 (:status me)))
        (is (= "alan@example.com" (get-in (edn-body me) [:user :email])))))))

(deftest todo-crud-and-history
  (let [cookie (register! "ada@example.com")]
    (testing "todos require a session"
      (is (= 401 (:status (call :get "/api/todos")))))

    (testing "create, list, toggle, delete"
      (let [created (edn-body (call :post "/api/todos"
                                    :body {:title "ship the demo"} :cookie cookie))]
        (is (= "ship the demo" (:title created)))
        (is (false? (:done created)))
        (is (instance? UUID (:id created)))
        (is (instance? Date (:created-at created)) "#inst survives the wire too")

        (is (= ["ship the demo"]
               (map :title (edn-body (call :get "/api/todos" :cookie cookie)))))

        (let [patched (edn-body (call :patch (str "/api/todos/" (:id created))
                                      :body {:done true} :cookie cookie))]
          (is (true? (:done patched))))

        (testing "history holds both states of :todo/done — the audit trail is free"
          (let [history  (edn-body (call :get (str "/api/todos/" (:id created) "/history")
                                         :cookie cookie))
                done-ops (filter #(= :todo/done (:attribute %)) history)]
            (is (some #(and (false? (:value %)) (= :set (:op %))) done-ops))
            (is (some #(and (true? (:value %)) (= :set (:op %))) done-ops))))

        (is (= 200 (:status (call :delete (str "/api/todos/" (:id created)) :cookie cookie))))
        (is (empty? (edn-body (call :get "/api/todos" :cookie cookie))))))

    (testing "validation guards the todo edge too"
      (is (= 400 (:status (call :post "/api/todos" :body {:title ""} :cookie cookie))))
      (is (= 400 (:status (call :post "/api/todos" :body {} :cookie cookie)))))))

(deftest ownership
  (let [ada-cookie   (register! "ada@example.com")
        grace-cookie (register! "grace@example.com")
        todo         (edn-body (call :post "/api/todos"
                                     :body {:title "ada's secret"} :cookie ada-cookie))]
    (testing "another user cannot see, change, or delete your todos"
      (is (empty? (edn-body (call :get "/api/todos" :cookie grace-cookie))))
      (is (= 404 (:status (call :patch (str "/api/todos/" (:id todo))
                                :body {:done true} :cookie grace-cookie))))
      (is (= 404 (:status (call :delete (str "/api/todos/" (:id todo))
                                :cookie grace-cookie))))
      (is (= 404 (:status (call :get (str "/api/todos/" (:id todo) "/history")
                                :cookie grace-cookie)))))))

(deftest openapi-doc
  (testing "the OpenAPI spec is generated from the same malli schemas"
    (let [response (call :get "/api/openapi.json" :accept "application/json")
          doc      (json/read-value (slurp (:body response)))]
      (is (= 200 (:status response)))
      (is (= "todos API" (get-in doc ["info" "title"])))
      (testing "every endpoint is documented"
        (is (= #{"/api/auth/register" "/api/auth/login" "/api/auth/logout" "/api/auth/me"
                 "/api/todos" "/api/todos/{id}" "/api/todos/{id}/history" "/api/weather"}
               (set (keys (get doc "paths"))))))
      (testing "request schema details survive into the doc"
        (let [password (get-in doc ["paths" "/api/auth/register" "post" "requestBody"
                                    "content" "application/json" "schema"
                                    "properties" "password"])]
          (is (= 8 (get password "minLength")))
          (is (= 128 (get password "maxLength")))))
      (testing "query coercion shows up with its bounds"
        (let [lat (->> (get-in doc ["paths" "/api/weather" "get" "parameters"])
                       (filter #(= "lat" (get % "name")))
                       first)]
          (is (= -90.0 (get-in lat ["schema" "minimum"]))))))))

(deftest content-negotiation
  (testing "the same endpoint serves JSON to a client that asks for it"
    (let [cookie (register! "ada@example.com")
          _      (call :post "/api/todos" :body {:title "speak both"} :cookie cookie)
          response (call :get "/api/todos" :cookie cookie :accept "application/json")
          [todo]   (json/read-value (slurp (:body response)) json/keyword-keys-object-mapper)]
      (is (= 200 (:status response)))
      (is (= "speak both" (:title todo)))
      (is (string? (:id todo)) "in JSON the uuid degrades to a string — the EDN edge keeps the type"))))
