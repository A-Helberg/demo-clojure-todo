(ns todo.server.system
  "System wiring with integrant: each top-level component (database
  connection, ring app, web server) is declared as data, with ig/ref
  marking the dependencies — comparable to the DI container registrations
  in an ASP.NET Program.cs, minus the reflection."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [todo.server.db :as db]
            [todo.server.routes :as routes]))

(def default-session-secret "dev-only-insecure-secret")

(defn config []
  {:todo/conn   {:storage-dir (or (System/getenv "TODO_DATA_DIR") "data")}
   :todo/app    {:conn           (ig/ref :todo/conn)
                 :session-secret (or (System/getenv "TODO_SESSION_SECRET")
                                     default-session-secret)}
   :todo/server {:app  (ig/ref :todo/app)
                 :port (parse-long (or (System/getenv "PORT") "3000"))}})

(defmethod ig/init-key :todo/conn [_ {:keys [storage-dir]}]
  (db/connect! {:storage-dir (if (= storage-dir :mem)
                               :mem
                               (.getAbsolutePath (io/file storage-dir)))}))

(defmethod ig/init-key :todo/app [_ {:keys [session-secret] :as opts}]
  (when (= session-secret default-session-secret)
    (println "WARNING: using the built-in dev session secret;"
             "set TODO_SESSION_SECRET in any real deployment."))
  (routes/app opts))

(defmethod ig/init-key :todo/server [_ {:keys [app port]}]
  (println (str "todo app listening on http://localhost:" port))
  (jetty/run-jetty app {:port port :join? false}))

(defmethod ig/halt-key! :todo/server [_ server]
  (.stop server))
