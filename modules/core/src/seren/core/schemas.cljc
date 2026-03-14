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
;; Reviews are scheduled using SM-2 spaced repetition intervals.

;; --- Score schemas (Phase 4) ---
;; Scores record how well the user recalled content during a review session.
