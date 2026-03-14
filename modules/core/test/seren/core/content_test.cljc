(ns seren.core.content-test
  "Tests for content processing — normalisation, chunking, heading extraction.

   TDD: these tests were written before the implementation.
   See: https://clojure.org/guides/test"
  (:require [clojure.test :refer [deftest is testing]]
            [seren.core.content :as content]
            [malli.core :as m]
            [seren.core.schemas :as schemas]))

;; --- validate-content-input ---

(deftest validate-content-input-test
  (testing "valid input with text"
    (let [result (content/validate-content-input {:text "Some article content"})]
      (is (:valid? result))))

  (testing "valid input with url"
    (let [result (content/validate-content-input {:url "https://example.com"})]
      (is (:valid? result))))

  (testing "invalid when both url and text are missing"
    (let [result (content/validate-content-input {})]
      (is (not (:valid? result)))
      (is (some? (:reason result)))))

  (testing "invalid when text is blank"
    (let [result (content/validate-content-input {:text "   "})]
      (is (not (:valid? result))))))

;; --- normalize-text ---

(deftest normalize-text-test
  (testing "trims leading/trailing whitespace"
    (is (= "hello world" (content/normalize-text "  hello world  "))))

  (testing "collapses multiple spaces into one"
    (is (= "hello world" (content/normalize-text "hello   world"))))

  (testing "collapses mixed whitespace (tabs, newlines within a line)"
    (is (= "hello world" (content/normalize-text "hello \t world"))))

  (testing "preserves paragraph breaks (double newlines)"
    (let [result (content/normalize-text "paragraph one.\n\nparagraph two.")]
      (is (re-find #"paragraph one" result))
      (is (re-find #"paragraph two" result)))))

;; --- chunk-text ---

(deftest chunk-text-test
  (testing "splits text into paragraph-based chunks"
    (let [text "First paragraph here.\n\nSecond paragraph here.\n\nThird paragraph."
          chunks (content/chunk-text text)]
      (is (= 3 (count chunks)))
      (is (= 0 (:index (first chunks))))
      (is (= 1 (:index (second chunks))))
      (is (= 2 (:index (nth chunks 2))))))

  (testing "single paragraph produces one chunk"
    (let [chunks (content/chunk-text "Just one paragraph.")]
      (is (= 1 (count chunks)))))

  (testing "empty text produces no chunks"
    (let [chunks (content/chunk-text "")]
      (is (empty? chunks))))

  (testing "each chunk matches the Chunk schema"
    (let [chunks (content/chunk-text "One.\n\nTwo.\n\nThree.")]
      (doseq [chunk chunks]
        (is (m/validate schemas/Chunk chunk))))))

;; --- extract-headings ---

(deftest extract-headings-test
  (testing "extracts markdown-style headings"
    (let [text "# Main Title\n\nSome content.\n\n## Section One\n\nMore content.\n\n### Subsection"
          headings (content/extract-headings text)]
      (is (= 3 (count headings)))
      (is (= {:level 1 :text "Main Title"} (first headings)))
      (is (= {:level 2 :text "Section One"} (second headings)))
      (is (= {:level 3 :text "Subsection"} (nth headings 2)))))

  (testing "returns empty vector when no headings"
    (is (= [] (content/extract-headings "Just plain text."))))

  (testing "each heading matches the Heading schema"
    (let [headings (content/extract-headings "# Title\n\n## Subtitle")]
      (doseq [h headings]
        (is (m/validate schemas/Heading h))))))

;; --- summarize ---

(deftest summarize-test
  (testing "produces a shorter summary from longer text"
    (let [long-text (str "Clojure is a dynamic, functional programming language "
                         "that runs on the Java Virtual Machine. It emphasises "
                         "immutable data structures and first-class functions. "
                         "Rich Hickey created Clojure in 2007 as a modern Lisp "
                         "for the JVM platform.")
          summary (content/summarize long-text)]
      (is (string? summary))
      (is (pos? (count summary)))
      ;; Summary should be shorter than the original
      (is (< (count summary) (count long-text)))))

  (testing "short text returns itself as the summary"
    (is (= "Short text." (content/summarize "Short text.")))))

;; --- extract-tags ---

(deftest extract-tags-test
  (testing "extracts significant words as tags"
    (let [tags (content/extract-tags "Clojure is a functional programming language for the JVM")]
      (is (vector? tags))
      (is (pos? (count tags)))
      ;; Should include significant words, not stopwords
      (is (some #{"clojure"} tags))
      (is (not (some #{"is" "a" "the" "for"} tags)))))

  (testing "returns empty vector for blank input"
    (is (= [] (content/extract-tags "")))))

;; --- process-content (integration of above) ---

(deftest process-content-test
  (testing "full pipeline: input → Content entity"
    (let [input {:text (str "# Learning Clojure\n\n"
                            "Clojure is a functional language on the JVM.\n\n"
                            "## Immutability\n\n"
                            "All data structures are immutable by default.")
                 :title "Learning Clojure"}
          result (content/process-content input)]
      (is (m/validate schemas/Content result))
      (is (= "Learning Clojure" (:title result)))
      (is (pos? (count (:chunks result))))
      (is (pos? (count (:headings result))))
      (is (string? (:summary result)))
      (is (vector? (:tags result)))))

  (testing "derives title from first heading when not provided"
    (let [result (content/process-content
                   {:text "# My Article\n\nSome content here."})]
      (is (= "My Article" (:title result)))))

  (testing "uses fallback title when no heading and no title"
    (let [result (content/process-content
                   {:text "Just plain text without headings."})]
      (is (string? (:title result)))
      (is (pos? (count (:title result)))))))
