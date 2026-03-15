(ns seren.core.review-test
  "Tests for review scaffold generators and scaffold selection.

   TDD: these tests were written before the implementation.
   Scaffolds are progressively stronger hints offered during free recall
   when the user has struggled with the material previously.

   See plan.md § 'Review Flow: Free Recall + Scaffolding'
   See: https://clojure.org/guides/test"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [seren.core.review :as review]))

;; --- select-scaffold-level ---

(deftest select-scaffold-level-quality-test
  (testing "quality >= 3 → no scaffold (good recall)"
    (is (= :none (review/select-scaffold-level {:quality 5 :interval 1})))
    (is (= :none (review/select-scaffold-level {:quality 4 :interval 10})))
    (is (= :none (review/select-scaffold-level {:quality 3 :interval 20}))))

  (testing "quality 2 → headings scaffold"
    (is (= :headings (review/select-scaffold-level {:quality 2 :interval 6}))))

  (testing "quality 1 → summary scaffold"
    (is (= :summary (review/select-scaffold-level {:quality 1 :interval 6}))))

  (testing "quality 0 → keyword-blanks scaffold (strongest hint)"
    (is (= :keyword-blanks (review/select-scaffold-level {:quality 0 :interval 6})))))

(deftest select-scaffold-level-long-interval-test
  (testing "interval > 30 days → keyword-blanks regardless of quality"
    (is (= :keyword-blanks (review/select-scaffold-level {:quality nil :interval 31})))
    (is (= :keyword-blanks (review/select-scaffold-level {:quality nil :interval 60})))))

(deftest select-scaffold-level-first-review-test
  (testing "nil quality (first review) with short interval → no scaffold"
    (is (= :none (review/select-scaffold-level {:quality nil :interval 0})))
    (is (= :none (review/select-scaffold-level {:quality nil :interval 10})))))

;; --- headings-scaffold ---

(deftest headings-scaffold-test
  (testing "returns headings as a structured prompt"
    (let [content {:headings [{:level 1 :text "Introduction"}
                              {:level 2 :text "Key Concepts"}
                              {:level 2 :text "Examples"}]
                   :title "Test Article"}
          result (review/headings-scaffold content)]
      (is (string? result))
      (is (str/includes? result "Introduction"))
      (is (str/includes? result "Key Concepts"))
      (is (str/includes? result "Examples"))))

  (testing "returns fallback when no headings"
    (let [result (review/headings-scaffold {:headings [] :title "My Article"})]
      (is (string? result))
      (is (str/includes? result "My Article")))))

;; --- summary-scaffold ---

(deftest summary-scaffold-test
  (testing "returns the content summary"
    (let [result (review/summary-scaffold {:summary "A functional language on the JVM."})]
      (is (string? result))
      (is (str/includes? result "functional language"))))

  (testing "handles missing summary gracefully"
    (let [result (review/summary-scaffold {:summary ""})]
      (is (string? result)))))

;; --- keyword-blanks-scaffold ---

(deftest keyword-blanks-scaffold-test
  (testing "replaces significant words with blanks"
    (let [result (review/keyword-blanks-scaffold
                   {:source-text "Clojure is a functional programming language for the JVM."
                    :tags ["clojure" "functional" "programming" "language"]})]
      (is (string? result))
      ;; Significant words should be replaced with blanks
      (is (str/includes? result "____"))
      ;; Stopwords should remain
      (is (str/includes? result "is"))
      (is (str/includes? result "for"))
      (is (str/includes? result "the"))))

  (testing "blanks at most ~40% of significant words to keep it useful"
    (let [result (review/keyword-blanks-scaffold
                   {:source-text "One two three four five six seven eight nine ten."
                    :tags ["one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten"]})
          blank-count (count (re-seq #"____" result))]
      ;; Should blank some but not all words
      (is (pos? blank-count))
      (is (<= blank-count 5)))))

;; --- generate-scaffold ---

(deftest generate-scaffold-dispatch-test
  (testing "dispatches to correct generator based on level"
    (let [content {:title "Test"
                   :headings [{:level 1 :text "Title"}]
                   :summary "A summary."
                   :source-text "Some text here."
                   :tags ["text"]}]
      (is (nil? (review/generate-scaffold :none content)))
      (is (string? (review/generate-scaffold :headings content)))
      (is (string? (review/generate-scaffold :summary content)))
      (is (string? (review/generate-scaffold :keyword-blanks content))))))
