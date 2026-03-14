(ns seren.app.main
  "Application layer — wires Core and Adapter together.
   Orchestrates content ingestion, review scheduling, and scoring
   by calling pure functions (Core) and side-effectful operations (Adapter).

   See plan.md for the full orchestration flow:
   ingest → schedule → review → score"
  (:require [seren.schemas.common :as common]))

;; Orchestration functions will be added here as Core and Adapter
;; modules are implemented in Phase 1+.
;;
;; Planned functions:
;;   ingest-content!  — validate input, chunk content, persist, schedule first review
;;   start-review!    — load due review, generate shape, return review session
;;   submit-recall!   — score transcript against source, update schedule

(comment
  ;; Placeholder — will contain REPL examples as functions are added
  :ok)
