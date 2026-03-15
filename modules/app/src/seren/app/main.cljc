(ns seren.app.main
  "Application layer — wires Core and Adapter together.
   Orchestrates content ingestion, review scheduling, and review completion
   by calling pure functions (Core) and side-effectful operations (Adapter).

   Phase 2 adds: automatic review creation on ingest, review retrieval,
   and review completion (applying quality scores via SM-2).

   See plan.md for the full orchestration flow:
   ingest → schedule → review → score"
  (:require [malli.core :as m]
            [seren.core.content :as content]
            [seren.core.scheduler :as sched]
            [seren.core.schemas :as schemas]
            [seren.adapter.content-store :as content-store]
            [seren.adapter.review-store :as review-store]
            [seren.schemas.common :as common]))

;; --- Configuration ---

(def AppConfig
  "Configuration map for the application.
   Phase 2 adds :review-dir for review persistence alongside :store-dir."
  [:map
   [:store-dir :string]
   [:review-dir :string]])

;; --- Helpers ---

(defn- now-ms
  "Returns the current time in milliseconds.
   Isolated here so it's the only impure call in the App layer."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

;; --- Ingestion ---

(def IngestInput
  "Input for content ingestion via the App layer."
  [:map
   [:store-dir :string]
   [:review-dir {:optional true} [:maybe :string]]
   [:text {:optional true} [:maybe :string]]
   [:title {:optional true} [:maybe :string]]
   [:url {:optional true} [:maybe :string]]])

(def IngestResult
  "Result of a content ingestion attempt.
   Phase 2: on success, also returns the initial :review."
  [:or
   [:map
    [:success :boolean]
    [:content schemas/Content]
    [:review {:optional true} [:maybe schemas/Review]]]
   [:map
    [:success :boolean]
    [:reason :string]]])

(defn ingest-content!
  "Ingests new content: validates input, processes text, persists to store,
   and creates an initial review scheduled for immediate practice.

   Returns {:success true :content ... :review ...} or {:success false :reason ...}.

   Phase 2 change: now also creates an initial Review entity via the scheduler,
   so new content appears in the 'due for review' dashboard immediately."
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
                         :title (:title input)})
            _         (content-store/save-content! (:store-dir input) processed)
            ;; Phase 2: create initial review if review-dir is configured
            review    (when-let [review-dir (:review-dir input)]
                        (let [r (sched/initial-review (:id processed) (now-ms))]
                          (review-store/save-review! review-dir r)
                          r))]
        {:success true :content processed :review review}))))

(m/=> ingest-content! [:=> [:cat IngestInput] IngestResult])

;; --- Content retrieval ---

(defn get-content
  "Retrieves a single content item by ID."
  [config content-id]
  (content-store/load-content (:store-dir config) content-id))

(m/=> get-content [:=> [:cat AppConfig common/Id] [:maybe schemas/Content]])

(defn list-all-content
  "Lists all ingested content, newest first."
  [config]
  (content-store/list-contents (:store-dir config)))

(m/=> list-all-content [:=> [:cat AppConfig] [:vector schemas/Content]])

;; --- Review retrieval ---

(defn list-due-reviews
  "Returns reviews that are due for practice right now.
   Sorted by due-at ascending (most overdue first).
   This powers the dashboard's 'due for review' section."
  [config]
  (review-store/list-due-reviews (:review-dir config) (now-ms)))

(m/=> list-due-reviews [:=> [:cat AppConfig] [:vector schemas/Review]])

(defn list-all-reviews
  "Returns all reviews, sorted by due-at ascending.
   Includes completed reviews for history/stats."
  [config]
  (review-store/list-reviews (:review-dir config)))

(m/=> list-all-reviews [:=> [:cat AppConfig] [:vector schemas/Review]])

(defn get-review
  "Retrieves a single review by ID."
  [config review-id]
  (review-store/load-review (:review-dir config) review-id))

(m/=> get-review [:=> [:cat AppConfig common/Id] [:maybe schemas/Review]])

;; --- Review completion ---

(defn complete-review!
  "Completes a review by applying a quality score (0-5) via SM-2.

   1. Loads the existing review
   2. Applies the quality score to compute new interval/ease-factor
   3. Saves the updated (completed) review
   4. Creates a new :pending review for the next session at the computed due-at

   Returns {:success true :completed-review ... :next-review ...}
   or {:success false :reason ...}."
  [config review-id quality]
  (if-let [review (review-store/load-review (:review-dir config) review-id)]
    (let [ts          (now-ms)
          completed   (sched/apply-review review quality ts)
          _           (review-store/save-review! (:review-dir config) completed)
          ;; Create the next review for this content at the new due date
          next-review (-> (sched/initial-review (:content-id review) (:due-at completed))
                          (assoc :interval    (:interval completed)
                                 :ease-factor (:ease-factor completed)
                                 :repetitions (:repetitions completed)
                                 :shape       (case (:repetitions completed)
                                                0 :headings-only
                                                1 :summary
                                                2 :keyword-blanks
                                                :free-recall)))
          _           (review-store/save-review! (:review-dir config) next-review)]
      {:success          true
       :completed-review completed
       :next-review      next-review})
    {:success false
     :reason  (str "Review not found: " review-id)}))

(m/=> complete-review!
      [:=> [:cat AppConfig common/Id schemas/Quality]
       [:or
        [:map [:success :boolean]
              [:completed-review schemas/Review]
              [:next-review schemas/Review]]
        [:map [:success :boolean]
              [:reason :string]]]])

;; ---- REPL Examples ----
(comment
  (def config {:store-dir  "/tmp/seren-content"
               :review-dir "/tmp/seren-reviews"})

  ;; Ingest content — now also creates a review
  (ingest-content! {:store-dir  "/tmp/seren-content"
                    :review-dir "/tmp/seren-reviews"
                    :text "# Clojure\n\nA functional language.\n\n## Immutability\n\nAll data is immutable."
                    :title "Clojure Guide"})
  ;; => {:success true, :content {...}, :review {...}}

  ;; List reviews due now
  (list-due-reviews config)

  ;; Complete a review with quality 4
  (complete-review! config "review-id-here" 4))
