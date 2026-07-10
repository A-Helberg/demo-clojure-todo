(ns todo.server.handlers
  "Route handlers. Each is a function of the Datomic connection returning a
  ring handler — plain functions from request map to response map, which is
  why the tests in test/todo/server/api_test.clj need no running server.
  By the time a request arrives here, reitit has already malli-validated
  and coerced :parameters, so handlers work with trusted data only."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [todo.server.auth :as auth]
            [todo.server.db :as db]
            [todo.server.weather :as weather]))

;;; auth

(defn register [conn]
  (fn [{:keys [parameters]}]
    (let [{:keys [email password]} (:body parameters)
          email (str/lower-case email)]
      (if (db/user-by-email (d/db conn) email)
        {:status 409 :body {:error "an account with that email already exists"}}
        (try
          (let [user (db/create-user! conn {:email         email
                                            :password-hash (auth/hash-password password)})]
            {:status  201
             :body    {:user user}
             :session {:user-id (:id user)}})
          ;; Lost a race with a concurrent registration: the db's
          ;; :db.unique/value constraint on email is the real guarantee.
          (catch clojure.lang.ExceptionInfo _
            {:status 409 :body {:error "an account with that email already exists"}}))))))

(defn login [conn]
  (fn [{:keys [parameters]}]
    (let [{:keys [email password]} (:body parameters)
          user (db/user-by-email (d/db conn) (str/lower-case email))]
      (if (and user (auth/verify-password password (:user/password-hash user)))
        {:status  200
         :body    {:user {:id (:user/id user) :email (:user/email user)}}
         :session {:user-id (:user/id user)}}
        {:status 401 :body {:error "invalid email or password"}}))))

(defn logout [_request]
  {:status 200 :body {:ok true} :session nil})

(defn me [conn]
  (fn [{:keys [user-id]}]
    (if-let [user (db/user-by-id (d/db conn) user-id)]
      {:status 200 :body {:user {:id (:user/id user) :email (:user/email user)}}}
      {:status 401 :body {:error "authentication required"}})))

;;; todos — :user-id is put on the request by auth/wrap-require-user

(defn list-todos [conn]
  (fn [{:keys [user-id]}]
    {:status 200 :body (db/todos-for-user (d/db conn) user-id)}))

(defn create-todo [conn]
  (fn [{:keys [user-id parameters]}]
    {:status 201 :body (db/create-todo! conn user-id (get-in parameters [:body :title]))}))

(defn patch-todo [conn]
  (fn [{:keys [user-id parameters]}]
    (let [{:keys [id]} (:path parameters)
          changes      (:body parameters)]
      (if-let [current (db/owned-todo (d/db conn) user-id id)]
        {:status 200 :body (if (seq changes)
                             (db/update-todo! conn id changes)
                             current)}
        {:status 404 :body {:error "no such todo"}}))))

(defn delete-todo [conn]
  (fn [{:keys [user-id parameters]}]
    (let [{:keys [id]} (:path parameters)]
      (if (db/owned-todo (d/db conn) user-id id)
        (do (db/delete-todo! conn id)
            {:status 200 :body {:deleted true}})
        {:status 404 :body {:error "no such todo"}}))))

(defn todo-history [conn]
  (fn [{:keys [user-id parameters]}]
    (let [{:keys [id]} (:path parameters)]
      (if (db/owned-todo (d/db conn) user-id id)
        {:status 200 :body (db/todo-history conn id)}
        {:status 404 :body {:error "no such todo"}}))))

;;; weather

(defn get-weather []
  (fn [{:keys [parameters]}]
    (let [{:keys [lat lon]} (:query parameters)]
      (try
        {:status 200 :body (weather/current lat lon)}
        (catch clojure.lang.ExceptionInfo e
          (if (= ::weather/upstream (:type (ex-data e)))
            {:status 502 :body {:error "weather provider unavailable"}}
            (throw e)))))))
