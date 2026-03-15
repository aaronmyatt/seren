(ns seren.core.review
  "Scaffold generators and scaffold selection for free-recall review sessions.

   Every review starts with free recall — the user speaks or types everything
   they remember, unassisted. Scaffolds are progressively stronger hints
   offered only when the user has struggled previously:

     Level 0: :none           — pure free recall (default)
     Level 1: :headings       — show section headings only
     Level 2: :summary        — show extractive summary
     Level 3: :keyword-blanks — show text with key terms blanked out

   Scaffold selection is driven by the SM-2 state (last quality score and
   interval length). The user can also manually request a hint during a
   session, which bumps the scaffold level up by one.

   All functions are pure — no IO, no atoms, no side effects.

   See plan.md § 'Review Flow: Free Recall + Scaffolding'
   See: https://www.learningscientists.org/retrieval-practice"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [seren.core.schemas :as schemas]))

;; --- Scaffold selection ---

(defn select-scaffold-level
  "Determines the scaffold level for a review session based on SM-2 state.

   Logic:
   - If interval > 30 days (very long gap) → :keyword-blanks (strongest hint)
   - If quality is nil and interval ≤ 30 → :none (first review, try unassisted)
   - quality >= 3 (passed last time) → :none
   - quality 2 (barely familiar) → :headings
   - quality 1 (barely remembered) → :summary
   - quality 0 (total blackout) → :keyword-blanks

   See plan.md § 'Review Flow: Free Recall + Scaffolding' for the full table."
  [{:keys [quality interval]}]
  (cond
    ;; Very long interval — content is likely forgotten, offer strongest hint
    (> interval 30)     :keyword-blanks
    ;; First review (nil quality) — try unassisted free recall
    (nil? quality)      :none
    ;; Based on last quality score
    (>= quality 3)      :none
    (= quality 2)       :headings
    (= quality 1)       :summary
    :else               :keyword-blanks))

(m/=> select-scaffold-level
      [:=> [:cat [:map
                  [:quality [:maybe schemas/Quality]]
                  [:interval :int]]]
       schemas/ScaffoldLevel])

;; --- Scaffold generators ---
;; Each generator takes a Content entity and returns a hint string.
;; These are pure functions — the Content is already fully processed.

(defn headings-scaffold
  "Generates a headings-only scaffold: shows the content's section structure
   without any body text. This is the lightest hint — structural cues jog
   memory without giving away content.

   If no headings exist, falls back to showing the title as the only cue."
  [content]
  (let [headings (:headings content)]
    (if (seq headings)
      (->> headings
           (map (fn [{:keys [level text]}]
                  ;; Indent by heading level for visual hierarchy
                  ;; See: https://spec.commonmark.org/0.31.2/#atx-headings
                  (str (str/join (repeat level "#")) " " text)))
           (str/join "\n"))
      ;; Fallback: just the title as a minimal cue
      (str "Topic: " (:title content)))))

(m/=> headings-scaffold [:=> [:cat schemas/Content] :string])

(defn summary-scaffold
  "Generates a summary scaffold: shows the content's extractive summary.
   This gives the user the high-level gist so they can fill in the details."
  [content]
  (let [summary (:summary content)]
    (if (str/blank? summary)
      (str "Topic: " (:title content))
      summary)))

(m/=> summary-scaffold [:=> [:cat schemas/Content] :string])

(defn keyword-blanks-scaffold
  "Generates a keyword-blanks scaffold: shows the source text with key terms
   replaced by '____'. This is the strongest hint — almost the full text,
   but forces the user to actively recall the blanked-out terms.

   Blanks at most ~40% of the tags to keep the scaffold useful (too many
   blanks would be as hard as free recall; too few would be just reading).

   Implementation uses reduce over regex-matched tokens to stay pure (no atoms).
   See: https://en.wikipedia.org/wiki/Cloze_test"
  [content]
  (let [source       (:source-text content)
        tags         (set (:tags content))
        ;; Blank roughly 40% of the significant words found in tags
        blank-target (max 1 (#?(:clj  long
                                :cljs js/Math.floor)
                             (* 0.4 (count tags))))
        ;; Split source into tokens (words and non-words) preserving order.
        ;; re-seq gives us all matches; we reconstruct via split+interleave.
        ;; See: https://clojure.org/reference/other_functions#regex
        tokens       (re-seq #"[A-Za-z0-9-]+|[^A-Za-z0-9-]+" source)
        ;; Reduce over tokens, threading a count of blanks made so far
        result       (reduce
                       (fn [{:keys [parts blanked]} token]
                         (if (and (re-matches #"[A-Za-z0-9-]+" token)
                                  (contains? tags (str/lower-case token))
                                  (< blanked blank-target))
                           {:parts (conj parts "____") :blanked (inc blanked)}
                           {:parts (conj parts token)  :blanked blanked}))
                       {:parts [] :blanked 0}
                       tokens)]
    (str/join (:parts result))))

(m/=> keyword-blanks-scaffold [:=> [:cat schemas/Content] :string])

;; --- Dispatch ---

(defn generate-scaffold
  "Generates the scaffold content for a given level, or nil for :none.
   This is the main entry point used by the review session page.

   The review page always shows the free-recall prompt first. If scaffold
   is not :none, it also renders the scaffold output as a collapsible hint."
  [level content]
  (case level
    :none           nil
    :headings       (headings-scaffold content)
    :summary        (summary-scaffold content)
    :keyword-blanks (keyword-blanks-scaffold content)))

(m/=> generate-scaffold
      [:=> [:cat schemas/ScaffoldLevel schemas/Content] [:maybe :string]])

;; ---- REPL Examples ----
(comment
  (def sample-content
    {:id "c-1"
     :title "Learning Clojure"
     :source-text "Clojure is a functional programming language for the JVM. It emphasises immutable data structures."
     :headings [{:level 1 :text "Learning Clojure"}
                {:level 2 :text "Immutability"}]
     :summary "Clojure is a functional language emphasising immutability."
     :tags ["clojure" "functional" "programming" "language" "immutable"]
     :chunks [{:index 0 :text "Clojure is a functional programming language for the JVM."}
              {:index 1 :text "It emphasises immutable data structures."}]
     :created-at 1710000000000})

  ;; Select scaffold level based on SM-2 state
  (select-scaffold-level {:quality 4 :interval 6})
  ;; => :none

  (select-scaffold-level {:quality 1 :interval 6})
  ;; => :summary

  ;; Generate scaffolds
  (headings-scaffold sample-content)
  ;; => "# Learning Clojure\n## Immutability"

  (summary-scaffold sample-content)
  ;; => "Clojure is a functional language emphasising immutability."

  (keyword-blanks-scaffold sample-content)
  ;; => "____ is a ____ programming language for the JVM. ..."

  (generate-scaffold :none sample-content)
  ;; => nil

  (generate-scaffold :headings sample-content))
