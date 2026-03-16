(ns seren.core.recall-test
  "Tests for recall scoring: similarity, missed chunk detection, and quality derivation.

   TDD: these tests were written before the implementation.
   The scoring pipeline compares a user's recall transcript against the source
   material to derive an SM-2 quality score automatically.

   See plan.md § 'Voice Free-Recall Flow'
   See: https://clojure.org/guides/test"
  (:require [clojure.test :refer [deftest is testing]]
            [seren.core.recall :as recall]))

;; --- tokenize ---

(deftest tokenize-test
  (testing "splits text into lowercase word tokens"
    (is (= ["hello" "world"] (recall/tokenize "Hello World")))
    (is (= ["quick" "brown" "fox"] (recall/tokenize "  The  quick  brown  fox  "))))

  (testing "strips common stop words"
    (is (= ["clojure" "functional"] (recall/tokenize "Clojure is a functional")))
    (is (= ["concurrency" "easy"] (recall/tokenize "Concurrency is easy"))))

  (testing "handles empty and nil input"
    (is (= [] (recall/tokenize "")))
    (is (= [] (recall/tokenize nil)))))

;; --- token-similarity ---

(deftest token-similarity-test
  (testing "identical texts → 1.0"
    (is (= 1.0 (recall/token-similarity "Clojure is great" "Clojure is great"))))

  (testing "completely different texts → 0.0"
    (is (= 0.0 (recall/token-similarity "apple banana cherry" "dog elephant fox"))))

  (testing "partial overlap → between 0 and 1"
    (let [sim (recall/token-similarity
                "Clojure is a functional language"
                "Clojure is a programming language")]
      (is (>= sim 0.5))
      (is (< sim 1.0))))

  (testing "order-independent — recall can rearrange content"
    (let [sim (recall/token-similarity
                "functional language Clojure"
                "Clojure functional language")]
      (is (= 1.0 sim))))

  (testing "empty transcript → 0.0"
    (is (= 0.0 (recall/token-similarity "" "some source text"))))

  (testing "both empty → 1.0 (nothing to recall, nothing missed)"
    (is (= 1.0 (recall/token-similarity "" "")))))

;; --- find-missed-chunks ---

(deftest find-missed-chunks-test
  (testing "all chunks recalled → empty result"
    (let [chunks [{:index 0 :text "Clojure is functional"}
                  {:index 1 :text "Data is immutable"}]
          transcript "Clojure is functional and data is immutable"]
      (is (empty? (recall/find-missed-chunks transcript chunks)))))

  (testing "one chunk missed → returns it"
    (let [chunks [{:index 0 :text "Clojure is functional"}
                  {:index 1 :text "Data is immutable"}
                  {:index 2 :text "Concurrency is easy"}]
          transcript "Clojure is functional and data is immutable"]
      (is (= 1 (count (recall/find-missed-chunks transcript chunks))))
      (is (= 2 (:index (first (recall/find-missed-chunks transcript chunks)))))))

  (testing "threshold controls sensitivity"
    (let [chunks [{:index 0 :text "Clojure is a functional programming language"}]
          ;; partial recall — mentions "Clojure" and "functional" but not full chunk
          transcript "Clojure is functional"]
      ;; With low threshold, this partial recall should count
      (is (empty? (recall/find-missed-chunks transcript chunks {:threshold 0.3})))
      ;; With high threshold, it's considered missed
      (is (= 1 (count (recall/find-missed-chunks transcript chunks {:threshold 0.9})))))))

;; --- score-recall ---

(deftest score-recall-test
  (testing "returns similarity, quality, and missed chunks"
    (let [content {:source-text "Clojure is a functional language"
                   :chunks [{:index 0 :text "Clojure is a functional language"}]}
          result (recall/score-recall "Clojure is a functional language" content)]
      (is (contains? result :similarity))
      (is (contains? result :quality))
      (is (contains? result :missed-chunks))
      (is (= 1.0 (:similarity result)))
      (is (= 5 (:quality result)))
      (is (empty? (:missed-chunks result)))))

  (testing "empty transcript → quality 0"
    (let [result (recall/score-recall "" {:source-text "some content"
                                          :chunks [{:index 0 :text "some content"}]})]
      (is (= 0.0 (:similarity result)))
      (is (= 0 (:quality result)))))

  (testing "partial recall → intermediate quality"
    (let [content {:source-text "Clojure is a functional programming language for the JVM. It emphasises immutable data structures."
                   :chunks [{:index 0 :text "Clojure is a functional programming language for the JVM"}
                            {:index 1 :text "It emphasises immutable data structures"}]}
          result (recall/score-recall "Clojure is a functional language" content)]
      (is (> (:similarity result) 0.0))
      (is (< (:similarity result) 1.0))
      (is (>= (:quality result) 0))
      (is (<= (:quality result) 5)))))
