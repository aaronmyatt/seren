(ns seren.app.main
  "Application layer — wires Core and Adapter together.
   Orchestrates content ingestion by calling pure functions (Core)
   and side-effectful operations (Adapter).

   See plan.md for the full orchestration flow:
   ingest → schedule → review → score"
  (:require [malli.core :as m]
            [seren.core.content :as content]
            [seren.core.schemas :as schemas]
            [seren.adapter.content-store :as store]
            [seren.schemas.common :as common]))

;; --- Configuration ---

(def AppConfig
  "Configuration map for the application."
  [:map
   [:store-dir :string]])

;; --- Ingestion ---

(def IngestInput
  "Input for content ingestion via the App layer."
  [:map
   [:store-dir :string]
   [:text {:optional true} [:maybe :string]]
   [:title {:optional true} [:maybe :string]]
   [:url {:optional true} [:maybe :string]]])

(def IngestResult
  "Result of a content ingestion attempt."
  [:or
   [:map
    [:success :boolean]
    [:content schemas/Content]]
   [:map
    [:success :boolean]
    [:reason :string]]])

(defn ingest-content!
  "Ingests new content: validates input, processes text, persists to store.
   Returns {:success true :content ...} or {:success false :reason ...}.

   This is the main entry point for Phase 1 — manual paste ingestion.
   Later phases will add URL fetching (Adapter) before this step."
  [input]
  (let [validation (content/validate-content-input
                     {:text  (:text input)
                      :url   (:url input)
                      :title (:title input)})]
    (if-not (:valid? validation)
      {:success false :reason (:reason validation)}
      (let [processed (content/process-content
                        {:text  (:text input)
                         :url   (:url input)
                         :title (:title input)})]
        (store/save-content! (:store-dir input) processed)
        {:success true :content processed}))))

(m/=> ingest-content! [:=> [:cat IngestInput] IngestResult])

;; --- Retrieval ---

(defn get-content
  "Retrieves a single content item by ID."
  [config content-id]
  (store/load-content (:store-dir config) content-id))

(m/=> get-content [:=> [:cat AppConfig common/Id] [:maybe schemas/Content]])

(defn list-all-content
  "Lists all ingested content, newest first."
  [config]
  (store/list-contents (:store-dir config)))

(m/=> list-all-content [:=> [:cat AppConfig] [:vector schemas/Content]])

;; ---- REPL Examples ----
(comment
  (def config {:store-dir "/tmp/seren-content"})

  ;; Ingest content (happy path)
  (ingest-content! {:store-dir "/tmp/seren-content"
                    :text "# Clojure\n\nA functional language.\n\n## Immutability\n\nAll data is immutable."
                    :title "Clojure Guide"})
  ;; => {:success true, :content {...}}

  ;; Ingest with missing text
  (ingest-content! {:store-dir "/tmp/seren-content"})
  ;; => {:success false, :reason "Either :text or :url must be provided"}

  ;; List all content
  (list-all-content config))
