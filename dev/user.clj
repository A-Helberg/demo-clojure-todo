(ns user
  "REPL workflow: (go) starts the whole system, (reset) reloads changed
  namespaces and restarts it, (halt) stops it. Server restarts take well
  under a second — this is the loop you develop in, not rebuild-and-rerun."
  (:require [integrant.repl :as ig-repl]
            [todo.server.system :as system]))

(ig-repl/set-prep! #(system/config))

(defn go    [] (ig-repl/go))
(defn halt  [] (ig-repl/halt))
(defn reset [] (ig-repl/reset))

(defn run-tests
  "Run the backend suite from the REPL via kaocha — same runner, same
  tests.edn as `mise run test`. Tests hit the handler directly with a
  fresh in-memory Datomic, so they don't touch the running dev system.
  Also try (kaocha.repl/run 'todo.server.api-test) to focus one namespace."
  []
  ((requiring-resolve 'kaocha.repl/run-all)))

(comment
  (reset)

  ())
