(ns seren.core.schemas
  "Domain schemas for the Seren learning agent.
   These define the shape of data flowing through pure Core functions.

   Seren captures content the user consumes online, schedules spaced
   retrieval practice, and scores voice-driven free recall.

   See plan.md for the full data model and architecture."
  (:require [seren.schemas.common :as common]))

;; --- Content schemas ---
;; Content is ingested from URLs or pasted text, then chunked for review.

;; --- Review schemas ---
;; Reviews are scheduled using SM-2 spaced repetition intervals.

;; --- Score schemas ---
;; Scores record how well the user recalled content during a review session.
