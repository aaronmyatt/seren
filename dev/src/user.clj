(ns user
  "REPL entry point. Loads all modules for interactive development.

   Start a REPL with: bb nrepl
   Then this namespace is loaded automatically."
  (:require [seren.core.content :as content]
            [seren.core.scheduler :as sched]
            [seren.core.schemas :as core-schemas]
            [seren.adapter.content-store :as content-store]
            [seren.adapter.review-store :as review-store]
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

(defn restart!
  "Restarts the Ring server (useful after route changes)."
  []
  (stop!)
  (server/start! {:port 3000})
  :restarted)

;; ---- REPL Examples ----
(comment
  (start!)
  (stop!)
  (restart!)

  ;; Try the content pipeline
  (content/validate-content-input {:text "Hello world"})

  (content/process-content {:text "# Clojure\n\nA functional language.\n\n## Data\n\nImmutable."
                            :title "Clojure Guide"})

  ;; Ingest via the App layer (Phase 2: now also creates a review)
  (app/ingest-content! {:store-dir  ".seren-data/content"
                        :review-dir ".seren-data/reviews"
                        :text "# Test\n\nFirst paragraph.\n\nSecond paragraph."
                        :title "Test Article"})

  ;; List all content
  (app/list-all-content {:store-dir  ".seren-data/content"
                         :review-dir ".seren-data/reviews"})

  ;; Phase 2: list due reviews
  (app/list-due-reviews {:store-dir  ".seren-data/content"
                         :review-dir ".seren-data/reviews"})

  ;; Phase 2: SM-2 scheduling
  (sched/next-review {:interval 0 :ease-factor 2.5 :repetitions 0 :quality 4})
  ;; => {:interval 1, :ease-factor 2.5, :repetitions 1}

  (sched/similarity->quality 0.85)
  ;; => 4
  )
