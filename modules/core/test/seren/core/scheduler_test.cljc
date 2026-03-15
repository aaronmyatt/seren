(ns seren.core.scheduler-test
  "Tests for SM-2 spaced repetition scheduler — pure interval calculations.

   TDD: these tests were written before the implementation.
   The SM-2 algorithm has well-defined properties we can test:
   - Intervals grow monotonically with consecutive correct recalls
   - Ease factor adjusts based on quality
   - Failed recalls (quality < 3) reset the sequence

   See: https://en.wikipedia.org/wiki/SuperMemo#Algorithm_SM-2
   See: https://clojure.org/guides/test"
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [seren.core.scheduler :as sched]
            [seren.core.schemas :as schemas]))

;; --- next-review ---

(deftest next-review-first-correct-test
  (testing "first correct recall (quality >= 3) → interval 1 day"
    (let [result (sched/next-review {:interval    0
                                     :ease-factor 2.5
                                     :repetitions 0
                                     :quality     4})]
      (is (= 1 (:interval result)))
      (is (= 1 (:repetitions result)))
      (is (m/validate schemas/SchedulerOutput result)))))

(deftest next-review-second-correct-test
  (testing "second consecutive correct recall → interval 6 days"
    (let [result (sched/next-review {:interval    1
                                     :ease-factor 2.5
                                     :repetitions 1
                                     :quality     4})]
      (is (= 6 (:interval result)))
      (is (= 2 (:repetitions result))))))

(deftest next-review-third-correct-test
  (testing "third+ correct recall → interval scales by ease factor"
    (let [result (sched/next-review {:interval    6
                                     :ease-factor 2.5
                                     :repetitions 2
                                     :quality     4})]
      ;; round(6 * 2.5) = 15
      (is (= 15 (:interval result)))
      (is (= 3 (:repetitions result))))))

(deftest next-review-failed-recall-test
  (testing "quality < 3 resets repetitions and interval to 0"
    (let [result (sched/next-review {:interval    15
                                     :ease-factor 2.5
                                     :repetitions 3
                                     :quality     2})]
      (is (= 0 (:repetitions result)))
      (is (= 1 (:interval result))))))

(deftest next-review-ease-factor-adjustment-test
  (testing "ease factor adjusts based on quality"
    (let [;; Quality 5 (perfect) should increase ease factor
          perfect (sched/next-review {:interval 6 :ease-factor 2.5
                                      :repetitions 2 :quality 5})
          ;; Quality 3 (just passed) should decrease ease factor
          scraped (sched/next-review {:interval 6 :ease-factor 2.5
                                      :repetitions 2 :quality 3})]
      (is (> (:ease-factor perfect) 2.5)
          "Perfect recall should increase ease factor")
      (is (< (:ease-factor scraped) 2.5)
          "Barely-passing recall should decrease ease factor"))))

(deftest next-review-ease-factor-floor-test
  (testing "ease factor never drops below 1.3"
    (let [result (sched/next-review {:interval    6
                                     :ease-factor 1.3
                                     :repetitions 2
                                     :quality     3})]
      (is (>= (:ease-factor result) 1.3)))))

(deftest next-review-schema-conformance-test
  (testing "output always matches SchedulerOutput schema"
    (doseq [q (range 0 6)]
      (let [result (sched/next-review {:interval    6
                                       :ease-factor 2.5
                                       :repetitions 2
                                       :quality     q})]
        (is (m/validate schemas/SchedulerOutput result)
            (str "Schema validation failed for quality=" q))))))

;; --- interval-monotonicity property ---

(deftest interval-monotonicity-test
  (testing "consecutive correct recalls produce monotonically increasing intervals"
    (let [states (reduce
                   (fn [acc _]
                     (let [prev (last acc)
                           next-state (sched/next-review
                                        (assoc prev :quality 4))]
                       (conj acc next-state)))
                   [{:interval 0 :ease-factor 2.5 :repetitions 0}]
                   (range 6))]
      ;; Each interval should be >= the previous one
      (doseq [[a b] (partition 2 1 (map :interval states))]
        (is (<= a b)
            (str "Interval should be non-decreasing: " a " -> " b))))))

;; --- quality->score ---

(deftest quality-from-similarity-test
  (testing "similarity thresholds map to correct quality values"
    (is (= 5 (sched/similarity->quality 0.95)))
    (is (= 4 (sched/similarity->quality 0.80)))
    (is (= 3 (sched/similarity->quality 0.60)))
    (is (= 2 (sched/similarity->quality 0.40)))
    (is (= 1 (sched/similarity->quality 0.20)))
    (is (= 0 (sched/similarity->quality 0.10)))))

(deftest quality-from-similarity-boundary-test
  (testing "boundary values match the plan's threshold table"
    ;; See plan.md § "SM-2 Scheduling" for the threshold table
    (is (= 5 (sched/similarity->quality 0.90)))
    (is (= 4 (sched/similarity->quality 0.75)))
    (is (= 3 (sched/similarity->quality 0.55)))
    (is (= 2 (sched/similarity->quality 0.35)))
    (is (= 1 (sched/similarity->quality 0.15)))
    (is (= 0 (sched/similarity->quality 0.14)))))

;; --- initial-review ---

(deftest initial-review-test
  (testing "creates a review with default SM-2 starting state"
    (let [review (sched/initial-review "content-123" 1710000000000)]
      (is (= "content-123" (:content-id review)))
      (is (= :pending (:status review)))
      (is (= :none (:scaffold review)))
      (is (= 0 (:interval review)))
      (is (= 2.5 (:ease-factor review)))
      (is (= 0 (:repetitions review)))
      (is (nil? (:quality review)))
      (is (= 1710000000000 (:due-at review)))
      (is (string? (:id review)))
      (is (m/validate schemas/Review review)))))
