(ns user
  "REPL entry point. Loads all modules for interactive development.

   Start a REPL with: bb nrepl
   Then this namespace is loaded automatically."
  (:require [seren.core.schemas :as core-schemas]
            [seren.app.main :as app]
            [seren.app.server :as server]
            [seren.schemas.common :as common]
            [malli.core :as m]
            [malli.dev :as dev]))

(defn start!
  "Initialize development environment.
   Starts Malli instrumentation and the Ring web server."
  []
  (dev/start!)
  (server/start! {:port 3000})
  (println)
  (println "Seren dev environment started.")
  (println "  Ring server:  http://localhost:3000")
  (println "  PocketBase:   http://127.0.0.1:8090/_/  (run bb pb:start)")
  (println "  shadow-cljs:  run bb cljs:watch in another terminal")
  :ready)

(defn stop!
  "Stops the Ring server."
  []
  (server/stop!)
  :stopped)

(defn restart!
  "Restarts the Ring server (useful after route changes)."
  []
  (stop!)
  (server/start! {:port 3000})
  :restarted)

;; ---- REPL Examples ----
(comment
  ;; Start dev environment (Malli + Ring server)
  (start!)

  ;; Stop/restart server
  (stop!)
  (restart!)

  ;; Schemas are defined — inspect them
  common/Id
  common/Timestamp
  core-schemas/Content  ;; will exist after Phase 1
  )
