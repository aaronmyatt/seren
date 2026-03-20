(ns seren.app.main
  "Application layer — wires Core and Adapter together.
   Orchestrates content ingestion, review scheduling, and review completion
   by calling pure functions (Core) and side-effectful operations (Adapter).

   Phase 2 adds: automatic review creation on ingest, review retrieval,
   and review completion (applying quality scores via SM-2).

   See plan.md for the full orchestration flow:
   ingest → schedule → review → score"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [seren.core.content :as content]
            [seren.core.recall :as recall]
            [seren.core.review :as review]
            [seren.core.scheduler :as sched]
            [seren.core.schemas :as schemas]
            [seren.adapter.content-store :as content-store]
            [seren.adapter.review-store :as review-store]
            [seren.schemas.common :as common]
            ;; URL fetching is JVM-only — conditional require.
            ;; See: https://clojure.org/guides/reader_conditionals
            #?(:clj [seren.adapter.url-fetcher :as url-fetcher])))

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
   Phase 2: on success, also returns the initial :review.
   Phase 4.5: adds :existing flag when a duplicate URL is detected."
  [:or
   [:map
    [:success :boolean]
    [:content schemas/Content]
    [:review {:optional true} [:maybe schemas/Review]]
    [:existing {:optional true} [:maybe :boolean]]]
   [:map
    [:success :boolean]
    [:reason :string]
    [:message {:optional true} [:maybe :string]]]])

(defn- resolve-content-input
  "Resolves the content input by fetching a URL if needed.

   Rules:
   1. If :text is provided, pass through unchanged (:text wins)
   2. If only :url is provided, fetch + extract on JVM; error on CLJS
   3. Returns the resolved input map or an error map with :error key

   See plan-url-fetching.md § 'Flow Change'"
  #_{:clj-kondo/ignore [:unused-binding]}
  [{:keys [text url title] :as input}]
  (if-not (and (str/blank? text) (not (str/blank? url)))
    ;; Text provided (or no URL) — pass through unchanged
    input
    ;; URL provided but no text — fetch it
    #?(:clj
       (let [result (url-fetcher/fetch-and-extract url)]
         (if (:success result)
           (cond-> {:text  (:text result)
                    :title (or (when-not (str/blank? title) title)
                               (:title result))
                    :url   url}
             (:meta result) (assoc :meta (:meta result)))
           {:error   (:reason result)
            :message (:message result)}))
       :cljs
       {:error "URL fetching is not supported in the browser"})))

(defn ingest-content!
  "Ingests new content: resolves URL (if needed), validates input, processes
   text, persists to store, and creates an initial review.

   Phase 4.5 changes:
   - URL-only input triggers server-side fetch + extraction (JVM only)
   - Duplicate URLs return existing content with :existing true
   - Metadata from URL fetching is passed through to the Content entity

   Returns {:success true :content ... :review ...}
   or {:success true :content ... :existing true} for duplicates
   or {:success false :reason ...}"
  [input]
  ;; Step 1: Check for duplicate URL before doing any work
  (if-let [existing (when-not (str/blank? (:url input))
                      (content-store/find-by-url (:store-dir input) (:url input)))]
    ;; Duplicate URL — return existing content without re-ingesting.
    ;; A repeated URL is a learning signal: the user may have forgotten
    ;; they saved it. The UI can nudge them to review instead.
    {:success  true
     :content  existing
     :existing true}
    ;; Step 2: Resolve URL → text if needed
    (let [resolved (resolve-content-input input)]
      (if (:error resolved)
        {:success false
         :reason  (:error resolved)
         :message (:message resolved)}
        ;; Step 3: Validate + process + store (existing pipeline)
        (let [content-input {:text  (:text resolved)
                             :url   (or (:url resolved) (:url input))
                             :title (:title resolved)
                             :meta  (:meta resolved)}
              validation    (content/validate-content-input content-input)]
          (if-not (:valid? validation)
            {:success false :reason (:reason validation)}
            (let [processed (content/process-content content-input)
                  _         (content-store/save-content! (:store-dir input) processed)
                  ;; Create initial review if review-dir is configured
                  review    (when-let [review-dir (:review-dir input)]
                              (let [r (sched/initial-review (:id processed) (now-ms))]
                                (review-store/save-review! review-dir r)
                                r))]
              {:success true :content processed :review review})))))))

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

;; --- Review session ---

(defn get-review-session
  "Loads everything needed for a review session: the review, the associated
   content, and the pre-generated scaffold hint (if any).

   Returns {:success true :review ... :content ... :scaffold ...}
   or {:success false :reason ...}.

   The scaffold is generated by core/review based on the review's :scaffold
   level and the content data. :scaffold in the response is a string (the
   hint text) or nil if scaffold level is :none."
  [config review-id]
  (if-let [rev (review-store/load-review (:review-dir config) review-id)]
    (if-let [cont (content-store/load-content (:store-dir config) (:content-id rev))]
      {:success  true
       :review   rev
       :content  cont
       :scaffold (review/generate-scaffold (:scaffold rev) cont)}
      {:success false
       :reason  (str "Content not found for review: " (:content-id rev))})
    {:success false
     :reason  (str "Review not found: " review-id)}))

(m/=> get-review-session
      [:=> [:cat AppConfig common/Id]
       [:or
        [:map [:success :boolean]
              [:review schemas/Review]
              [:content schemas/Content]
              [:scaffold [:maybe :string]]]
        [:map [:success :boolean]
              [:reason :string]]]])

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
          ;; Create the next review for this content at the new due date.
          ;; Scaffold level is determined by how well the user recalled:
          ;; good recall → no scaffold, struggled → progressively stronger hints.
          ;; See plan.md § 'Review Flow: Free Recall + Scaffolding'
          next-review (-> (sched/initial-review (:content-id review) (:due-at completed))
                          (assoc :interval    (:interval completed)
                                 :ease-factor (:ease-factor completed)
                                 :repetitions (:repetitions completed)
                                 ;; Delegate scaffold selection to the pure function
                                 ;; See: seren.core.review/select-scaffold-level
                                 :scaffold    (review/select-scaffold-level
                                                {:quality  quality
                                                 :interval (:interval completed)})))
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

;; --- Ad-hoc review (Phase 4a) ---

(defn start-review!
  "Starts an ad-hoc review for a specific content item.

   If an existing pending/due review for this content exists, returns it
   (avoids creating duplicates). Otherwise creates a new review with
   default SM-2 starting state, due immediately.

   Returns {:success true :review {...} :created? bool}
   or {:success false :reason ...}.

   See plan.md § 'Phase 4a — Manual Review'"
  [config content-id]
  (if-let [_content (content-store/load-content (:store-dir config) content-id)]
    (let [existing (review-store/find-reviews-by-content
                     (:review-dir config) content-id)
          ;; Find an active (non-completed) review we can reuse
          active   (first (filter #(not= :completed (:status %)) existing))]
      (if active
        {:success  true
         :review   active
         :created? false}
        ;; No active review — create a fresh one, due now
        (let [review (sched/initial-review content-id (now-ms))]
          (review-store/save-review! (:review-dir config) review)
          {:success  true
           :review   review
           :created? true})))
    {:success false
     :reason  (str "Content not found: " content-id)}))

(m/=> start-review!
      [:=> [:cat AppConfig common/Id]
       [:or
        [:map [:success :boolean]
              [:review schemas/Review]
              [:created? :boolean]]
        [:map [:success :boolean]
              [:reason :string]]]])

;; --- Score and complete (Phase 4) ---

(defn score-and-complete-review!
  "Scores a recall transcript against source content, derives SM-2 quality
   automatically, and completes the review. This replaces the manual quality
   input from Phase 3.

   Pipeline:
   1. Load review + content
   2. Score transcript via core/recall (similarity + missed chunks)
   3. Map similarity → quality via scheduler/similarity->quality
   4. Complete the review with the derived quality (same as complete-review!)
   5. Return scoring details alongside the scheduling result

   Returns {:success true :similarity ... :quality ... :missed-chunks ...
            :completed-review ... :next-review ...}
   or {:success false :reason ...}

   See plan.md § 'Voice Free-Recall Flow'"
  [config review-id transcript]
  (if-let [rev (review-store/load-review (:review-dir config) review-id)]
    (if-let [cont (content-store/load-content (:store-dir config) (:content-id rev))]
      (let [;; Score the recall attempt (pure)
            score       (recall/score-recall transcript cont)
            quality     (:quality score)
            ;; Complete the review with derived quality (same logic as complete-review!)
            ts          (now-ms)
            completed   (sched/apply-review rev quality ts)
            _           (review-store/save-review! (:review-dir config) completed)
            next-review (-> (sched/initial-review (:content-id rev) (:due-at completed))
                            (assoc :interval    (:interval completed)
                                   :ease-factor (:ease-factor completed)
                                   :repetitions (:repetitions completed)
                                   :scaffold    (review/select-scaffold-level
                                                  {:quality  quality
                                                   :interval (:interval completed)})))
            _           (review-store/save-review! (:review-dir config) next-review)]
        {:success          true
         :similarity       (:similarity score)
         :quality          quality
         :missed-chunks    (:missed-chunks score)
         :completed-review completed
         :next-review      next-review})
      {:success false
       :reason  (str "Content not found for review: " (:content-id rev))})
    {:success false
     :reason  (str "Review not found: " review-id)}))

(m/=> score-and-complete-review!
      [:=> [:cat AppConfig common/Id :string]
       [:or
        [:map [:success :boolean]
              [:similarity :double]
              [:quality schemas/Quality]
              [:missed-chunks [:vector schemas/Chunk]]
              [:completed-review schemas/Review]
              [:next-review schemas/Review]]
        [:map [:success :boolean]
              [:reason :string]]]])

;; ---- REPL Examples ----
(comment
  (def config {:store-dir  "/tmp/seren-content"
               :review-dir "/tmp/seren-reviews"})

  ;; Ingest content from text — existing flow
  (ingest-content! {:store-dir  "/tmp/seren-content"
                    :review-dir "/tmp/seren-reviews"
                    :text "# Clojure\n\nA functional language.\n\n## Immutability\n\nAll data is immutable."
                    :title "Clojure Guide"})
  ;; => {:success true, :content {...}, :review {...}}

  ;; Phase 4.5: Ingest from URL — fetches + extracts article text
  (ingest-content! {:store-dir  "/tmp/seren-content"
                    :review-dir "/tmp/seren-reviews"
                    :url "https://clojure.org/about/rationale"})
  ;; => {:success true, :content {:title "...", :source-text "...", :meta {...}}, :review {...}}

  ;; Phase 4.5: Duplicate URL — returns existing content
  (ingest-content! {:store-dir  "/tmp/seren-content"
                    :review-dir "/tmp/seren-reviews"
                    :url "https://clojure.org/about/rationale"})
  ;; => {:success true, :content {...}, :existing true}

  ;; List reviews due now
  (list-due-reviews config)

  ;; Complete a review with quality 4
  (complete-review! config "review-id-here" 4)

  ;; Phase 4a: start an ad-hoc review for any content
  (start-review! config "content-id-here")
  ;; => {:success true, :review {...}, :created? true})
)
