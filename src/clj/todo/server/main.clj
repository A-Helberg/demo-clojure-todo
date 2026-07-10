(ns todo.server.main
  (:require [integrant.core :as ig]
            [todo.server.system :as system])
  (:gen-class))

(defn -main [& _args]
  (let [sys (ig/init (system/config))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(ig/halt! sys)))))
