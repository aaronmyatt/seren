(ns user
  "REPL entry point. Loads all modules for interactive development.

   Start a REPL with: bb nrepl
   Then this namespace is loaded automatically."
  (:require [fcis.core.user :as user]
            [fcis.core.schemas :as core-schemas]
            [fcis.adapter.user-store :as store]
            [fcis.app.main :as app]
            [fcis.app.server :as server]
            [fcis.schemas.common :as common]
            [malli.core :as m]
            [malli.dev :as dev]))

(defn start!
  "Initialize development environment.
   Starts Malli instrumentation and the Ring web server."
  []
  (dev/start!)
  (server/start! {:port 3000})
  (println)
  (println "FCIS dev environment started.")
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

  ;; Try core functions
  (user/validate-email "alice@example.com")
  ;; => {:valid? true, :reason nil}

  (user/create-user "Alice" "alice@example.com")
  ;; => {:id "..." :name "Alice" :email "alice@example.com" :created-at ...}

  ;; Try adapter functions
  (store/save-user! "/tmp/fcis-dev" (user/create-user "Bob" "bob@example.com"))

  (store/list-users "/tmp/fcis-dev")

  ;; Try app functions
  (app/register-user! {:store-dir "/tmp/fcis-dev"
                        :name "Charlie"
                        :email "charlie@example.com"})
  )
