(ns dev
  "Entry point for the Procfile's server process: starts the whole system
  AND an nREPL server, so the app `mise run dev` is running is the same
  process your editor connects to — evaluate, (reset), (run-tests), all
  against the live server."
  (:require [nrepl.server :as nrepl]
            [user :as user]))

(defn -main [& _args]
  (let [port    (parse-long (or (System/getenv "NREPL_PORT") "7888"))
        handler (requiring-resolve 'cider.nrepl/cider-nrepl-handler)]
    (nrepl/start-server :port port :handler handler)
    (spit ".nrepl-port" (str port))
    (println (str "nREPL listening on " port " (.nrepl-port written for editor auto-detect)"))
    (user/go)))
