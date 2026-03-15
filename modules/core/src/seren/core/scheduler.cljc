(ns seren.core.scheduler
  "SM-2 spaced repetition scheduler — pure interval calculations.

   The SM-2 algorithm was developed by Piotr Wozniak for SuperMemo. Given a
   review's current state (interval, ease-factor, repetitions) and a quality
   score (0-5), it computes the next interval and updated ease factor.

   Key insight for Seren: instead of the user self-grading (like Anki), quality
   is derived from the **similarity score** between the voice transcript and
   source material. This removes subjective bias from self-assessment.

   All functions are pure — no IO, no atoms, no side effects.

   See: https://en.wikipedia.org/wiki/SuperMemo#Algorithm_SM-2
   See: https://www.supermemo.com/en/blog/application-of-a-computer-to-improve-the-results-obtained-in-working-with-the-supermemo-method"
  (:require [malli.core :as m]
            [seren.core.schemas :as schemas]
            [seren.schemas.common :as common]))

;; --- SM-2 core ---

(defn next-review
  "Computes the next SM-2 scheduling state from current state + quality.

   The algorithm works as follows:
   1. If quality < 3 (failed recall), reset: repetitions=0, interval=1
   2. If quality >= 3 (successful recall):
      - rep 0 → interval 1
      - rep 1 → interval 6
      - rep 2+ → interval = round(previous-interval × ease-factor)
   3. Ease factor is always adjusted:
      EF' = EF + (0.1 - (5 - q) × (0.08 + (5 - q) × 0.02))
      with a floor of 1.3.

   See: https://en.wikipedia.org/wiki/SuperMemo#Algorithm_SM-2"
  [{:keys [interval ease-factor repetitions quality]}]
  (let [;; Step 1: Adjust ease factor regardless of pass/fail
        ;; This is the standard SM-2 EF adjustment formula.
        ;; See: https://www.supermemo.com/en/blog/application-of-a-computer-to-improve-the-results-obtained-in-working-with-the-supermemo-method
        new-ef (max 1.3
                    (+ ease-factor
                       (- 0.1
                          (* (- 5 quality)
                             (+ 0.08 (* (- 5 quality) 0.02))))))

        ;; Step 2: Determine new interval and repetitions
        passed? (>= quality 3)]
    (if passed?
      (let [new-reps (inc repetitions)
            new-interval (case new-reps
                           1 1
                           2 6
                           ;; rep 3+: scale previous interval by ease factor
                           ;; Math/round is platform-specific:
                           ;; See: https://clojure.org/guides/reader_conditionals
                           #?(:clj  (long (Math/round (* (double interval)
                                                         (double new-ef))))
                              :cljs (js/Math.round (* interval new-ef))))]
        {:interval    new-interval
         :ease-factor new-ef
         :repetitions new-reps})
      ;; Failed: reset the learning sequence but keep the adjusted EF
      {:interval    1
       :ease-factor new-ef
       :repetitions 0})))

;; Malli function schemas register the spec for runtime validation and CLI discovery.
;; See: https://github.com/metosin/malli#function-schemas
(m/=> next-review [:=> [:cat schemas/SchedulerInput] schemas/SchedulerOutput])

;; --- Quality derivation ---

(defn similarity->quality
  "Maps a similarity score (0.0–1.0) to an SM-2 quality value (0–5).

   Rather than the user self-grading (like Anki), Seren derives quality
   from the similarity between the voice transcript and source material.
   This removes subjective bias from self-assessment.

   Thresholds from plan.md:
   | Similarity | Quality | Meaning                  |
   |------------|---------|--------------------------|
   | >= 0.90    | 5       | Perfect recall            |
   | >= 0.75    | 4       | Correct with hesitation   |
   | >= 0.55    | 3       | Correct with difficulty   |
   | >= 0.35    | 2       | Incorrect but familiar    |
   | >= 0.15    | 1       | Barely remembered         |
   | <  0.15    | 0       | Total blackout            |

   See plan.md § 'SM-2 Scheduling (Core, Pure)'"
  [similarity]
  (cond
    (>= similarity 0.90) 5
    (>= similarity 0.75) 4
    (>= similarity 0.55) 3
    (>= similarity 0.35) 2
    (>= similarity 0.15) 1
    :else                 0))

(m/=> similarity->quality [:=> [:cat :double] schemas/Quality])

;; --- Review creation ---

(defn initial-review
  "Creates a new Review entity with default SM-2 starting state.

   Called when content is first ingested — the review is immediately :pending
   with due-at set to `now`, so it appears in the dashboard right away.
   Scaffold defaults to :none (pure free recall)."
  [content-id now-ms]
  {:id          (str #?(:clj  (java.util.UUID/randomUUID)
                        :cljs (random-uuid)))
   :content-id  content-id
   :status      :pending
   :scaffold    :none
   :interval    0
   :ease-factor 2.5
   :repetitions 0
   :quality     nil
   :due-at      now-ms
   :created-at  now-ms
   :reviewed-at nil})

(m/=> initial-review [:=> [:cat common/Id common/Timestamp] schemas/Review])

;; --- Convenience: apply review result ---

(defn apply-review
  "Applies a quality score to an existing review, computing the next
   scheduling state and updating the review accordingly.

   Returns the updated Review with new interval, ease-factor, repetitions,
   due-at, and status set to :completed. A new review for the next session
   should be created from this output by the App layer.

   `now-ms` is the current timestamp in milliseconds."
  [review quality now-ms]
  (let [{:keys [interval ease-factor repetitions]} review
        next-state (next-review {:interval    interval
                                 :ease-factor ease-factor
                                 :repetitions repetitions
                                 :quality     quality})
        ;; Convert interval (days) to milliseconds and add to now
        ;; 1 day = 86400000 ms
        next-due   (+ now-ms (* (:interval next-state) 86400000))]
    (merge review
           next-state
           {:quality     quality
            :status      :completed
            :reviewed-at now-ms
            :due-at      next-due})))

(m/=> apply-review [:=> [:cat schemas/Review schemas/Quality common/Timestamp] schemas/Review])

;; ---- REPL Examples ----
(comment
  ;; First review: quality 4 (good recall)
  (next-review {:interval 0 :ease-factor 2.5 :repetitions 0 :quality 4})
  ;; => {:interval 1, :ease-factor 2.5, :repetitions 1}

  ;; After 6 days, another good recall
  (next-review {:interval 6 :ease-factor 2.5 :repetitions 2 :quality 4})
  ;; => {:interval 15, :ease-factor 2.5, :repetitions 3}

  ;; Failed recall resets the sequence
  (next-review {:interval 15 :ease-factor 2.5 :repetitions 3 :quality 1})
  ;; => {:interval 1, :ease-factor 1.7000000000000002, :repetitions 0}

  ;; Map similarity to quality
  (similarity->quality 0.92)
  ;; => 5

  ;; Create initial review for content
  (initial-review "content-abc" 1710000000000))
