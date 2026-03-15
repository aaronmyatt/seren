(ns seren.core.schemas
  "Domain schemas for the Seren learning agent.
   These define the shape of data flowing through pure Core functions.

   Seren captures content the user consumes online, schedules spaced
   retrieval practice, and scores voice-driven free recall.

   See plan.md for the full data model and architecture.
   See: https://github.com/metosin/malli#schema-types"
  (:require [seren.schemas.common :as common]))

;; --- Content schemas ---
;; Content is ingested from URLs or pasted text, then chunked for review.
;; A \"chunk\" is a paragraph-sized piece of text — the unit of review.

(def Url
  "A URL string. Used for content source tracking."
  [:string {:min 1}])

(def Chunk
  "A single reviewable chunk of content — typically one paragraph.
   Each chunk has an index (position in original) and the text itself."
  [:map
   [:index :int]
   [:text common/NonBlankString]])

(def Heading
  "A heading extracted from the source content.
   Level corresponds to h1=1, h2=2, etc."
  [:map
   [:level [:int {:min 1 :max 6}]]
   [:text common/NonBlankString]])

(def ContentInput
  "Raw input for content ingestion — either a URL or pasted text.
   At least one of :url or :text must be provided."
  [:map
   [:url {:optional true} [:maybe Url]]
   [:title {:optional true} [:maybe :string]]
   [:text {:optional true} [:maybe :string]]])

(def Content
  "A fully processed content entity ready for review scheduling.
   Created by the ingestion pipeline from ContentInput."
  [:map
   [:id common/Id]
   [:url {:optional true} [:maybe Url]]
   [:title common/NonBlankString]
   [:source-text common/NonBlankString]
   [:chunks [:vector Chunk]]
   [:headings [:vector Heading]]
   [:summary common/NonBlankString]
   [:tags [:vector :string]]
   [:created-at common/Timestamp]])

;; --- Review schemas (Phase 2) ---
;; Reviews are scheduled using the SM-2 spaced repetition algorithm.
;; Each review tracks a content item's position in the learning curve:
;; interval (days until next review), ease-factor (difficulty multiplier),
;; and repetition count. Quality (0-5) is derived from similarity scoring
;; in Phase 4 — until then, the user self-reports.
;;
;; See: https://en.wikipedia.org/wiki/SuperMemo#SM-2_algorithm
;; See plan.md § "SM-2 Scheduling (Core, Pure)"

(def ReviewStatus
  "A review is either :pending (not yet started), :due (ready for recall),
   or :completed (finished, scored). Status drives dashboard filtering."
  [:enum :pending :due :completed])

(def ScaffoldLevel
  "The level of scaffolding offered during a free-recall review session.
   Every review starts with free recall (the user speaks/types from memory).
   Scaffolds are progressively stronger hints for when the user has struggled:
     :none           → No hint (default, maximum retrieval practice benefit)
     :headings       → Show section headings only (lightest hint)
     :summary        → Show extractive summary (medium hint)
     :keyword-blanks → Show text with key terms blanked (strongest hint)
   See plan.md § 'Review Flow: Free Recall + Scaffolding'"
  [:enum :none :headings :summary :keyword-blanks])

(def Quality
  "SM-2 quality score (0-5). Maps to recall accuracy:
     5 = perfect recall, 0 = total blackout.
   In Phase 4, derived automatically from similarity; until then, self-reported.
   See plan.md § 'SM-2 Scheduling' quality mapping table."
  [:int {:min 0 :max 5}])

(def EaseFactor
  "SM-2 ease factor — a float ≥ 1.3 that scales the review interval.
   Starts at 2.5 and adjusts after each recall attempt.
   Lower = harder content (shorter intervals), higher = easier (longer intervals).
   See: https://en.wikipedia.org/wiki/SuperMemo#Algorithm_SM-2"
  [:double {:min 1.3}])

(def Review
  "A scheduled review for a content item. Tracks SM-2 state and
   determines when the user should next practice recalling this content."
  [:map
   [:id common/Id]
   [:content-id common/Id]
   [:status ReviewStatus]
   [:scaffold ScaffoldLevel]
   [:interval :int]                ;; days until next review (0 = new)
   [:ease-factor EaseFactor]
   [:repetitions :int]             ;; number of successful consecutive recalls
   [:quality {:optional true} [:maybe Quality]]  ;; last quality score (nil if unreviewed)
   [:due-at common/Timestamp]      ;; when this review becomes due
   [:created-at common/Timestamp]
   [:reviewed-at {:optional true} [:maybe common/Timestamp]]])

(def SchedulerInput
  "Input to the SM-2 next-review function: the current review state + quality."
  [:map
   [:interval :int]
   [:ease-factor EaseFactor]
   [:repetitions :int]
   [:quality Quality]])

(def SchedulerOutput
  "Output of the SM-2 next-review function: the updated scheduling state."
  [:map
   [:interval :int]
   [:ease-factor EaseFactor]
   [:repetitions :int]])

;; --- Score schemas (Phase 4) ---
;; Scores record how well the user recalled content during a review session.
