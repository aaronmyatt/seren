(ns user
  "REPL entry point. Loads all modules for interactive development.

   Start a REPL with: bb nrepl
   Then this namespace is loaded automatically.

   Key functions:
     (start!)   — start Malli instrumentation + Ring server
     (stop!)    — stop the Ring server
     (reset!)   — reload ALL changed namespaces + restart server
     (refresh)  — reload changed namespaces without restarting server

   IMPORTANT: After editing source files, always call (reset!) before
   testing via curl or the browser. Without this, the running JVM serves
   stale bytecode — your edits won't take effect.

   See: https://github.com/clojure/tools.namespace"
  (:require [seren.core.content :as content]
            [seren.core.recall :as recall]
            [seren.core.review :as review]
            [seren.core.scheduler :as sched]
            [seren.core.schemas :as core-schemas]
            [seren.adapter.content-store :as content-store]
            [seren.adapter.review-store :as review-store]
            [seren.app.main :as app]
            [seren.app.server :as server]
            [seren.schemas.common :as common]
            [malli.core :as m]
            [malli.dev :as dev]
            ;; tools.namespace does a dependency-order refresh of all changed
            ;; .clj files on the classpath. Unlike (require ... :reload), it
            ;; handles transitive dependencies correctly — if content.cljc
            ;; changes, anything that requires it also gets reloaded.
            ;; See: https://github.com/clojure/tools.namespace#repl-usage
            [clojure.tools.namespace.repl :as tn-repl]))

;; Tell tools.namespace to only scan our source directories, not test
;; directories or third-party code. This makes refresh faster and avoids
;; accidentally loading test namespaces.
;; See: https://github.com/clojure/tools.namespace#configuring-refresh
(tn-repl/set-refresh-dirs
  "shared/src"
  "modules/core/src"
  "modules/adapter/src"
  "modules/app/src"
  "dev/src")

(defn start!
  "Initialize development environment.
   Starts Malli instrumentation and the Ring web server."
  []
  (dev/start!)
  (server/start! {:port 3000})
  (println)
  (println "Seren dev environment started.")
  (println "  Ring server:  http://localhost:3000")
  (println "  Library:      http://localhost:3000/library")
  (println "  Dashboard:    http://localhost:3000/dashboard")
  (println "  PocketBase:   http://127.0.0.1:8090/_/  (run bb pb:start)")
  (println "  shadow-cljs:  run bb cljs:watch in another terminal")
  :ready)

(defn stop!
  "Stops the Ring server."
  []
  (server/stop!)
  :stopped)

(defn refresh
  "Reloads all changed namespaces in dependency order.
   Does NOT restart the server — use this when you only changed pure
   functions (Core layer) and want to test them directly in the REPL.

   Returns :ok on success, or prints the error and returns :error."
  []
  (let [result (tn-repl/refresh)]
    (if (instance? Throwable result)
      (do
        (println "Refresh failed:")
        (.printStackTrace result)
        :error)
      (do
        (println "Namespaces refreshed.")
        :ok))))

(defn reset!
  "The nuclear option: stops the server, reloads ALL changed namespaces
   in dependency order, then restarts the server with fresh code.

   Call this after editing ANY source file to ensure the running server
   reflects your changes. Without this, the JVM serves stale bytecode.

   This is the Clojure equivalent of 'save and restart' — but faster,
   because it only recompiles what changed.

   See: https://github.com/clojure/tools.namespace#repl-usage"
  []
  (stop!)
  (let [result (tn-repl/refresh)]
    (if (instance? Throwable result)
      (do
        (println "Refresh failed — server NOT restarted:")
        (.printStackTrace result)
        :error)
      (do
        (println "Namespaces refreshed. Restarting server...")
        ;; Re-resolve start! via the var because the user ns itself
        ;; may have been reloaded, invalidating the direct fn reference.
        ((requiring-resolve 'user/start!))))))

;; ---- REPL Examples ----
(comment
  ;; === Lifecycle ===
  (start!)                ; Start server + Malli instrumentation
  (stop!)                 ; Stop server
  (reset!)                ; Reload changed code + restart server
  (refresh)               ; Reload changed code only (no server restart)

  ;; === Quick smoke test after reset! ===
  ;; Verify the server is running fresh code by checking the build fingerprint:
  ;; curl http://localhost:3000/api/status
  ;; The :loaded-at timestamp changes on every reset!.

  ;; === Content pipeline ===
  (content/validate-content-input {:text "Hello world"})

  (content/process-content {:text "# Clojure\n\nA functional language.\n\n## Data\n\nImmutable."
                            :title "Clojure Guide"})

  ;; Ingest via the App layer (creates a review automatically)
  (app/ingest-content! {:store-dir  ".seren-data/content"
                        :review-dir ".seren-data/reviews"
                        :text "# Test\n\nFirst paragraph.\n\nSecond paragraph."
                        :title "Test Article"})

  ;; List all content
  (app/list-all-content {:store-dir  ".seren-data/content"
                         :review-dir ".seren-data/reviews"})

  ;; SM-2 scheduling
  (sched/next-review {:interval 0 :ease-factor 2.5 :repetitions 0 :quality 4})
  ;; => {:interval 1, :ease-factor 2.5, :repetitions 1}

  (sched/similarity->quality 0.85)
  ;; => 4
  )
