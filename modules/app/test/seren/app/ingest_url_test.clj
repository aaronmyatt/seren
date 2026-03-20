(ns seren.app.ingest-url-test
  "Integration tests for URL-based content ingestion (Phase 4.5).

   Tests the full flow: URL → fetch → extract → process → store → review.
   Also tests duplicate URL detection and the :existing flag.

   These tests make real HTTP requests — they verify the end-to-end
   pipeline, not just unit behavior.

   See plan-url-fetching.md for the design."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [seren.app.main :as app]
            [seren.adapter.content-store :as content-store]
            [malli.core :as m]
            [seren.core.schemas :as schemas]))

;; --- Test fixtures ---
;; Fresh temp directories for content and review stores per test.

(def ^:dynamic *config* nil)

(defn temp-dirs-fixture [f]
  (let [base    (str (System/getProperty "java.io.tmpdir")
                     "/seren-app-test-" (System/nanoTime))
        content (str base "/content")
        reviews (str base "/reviews")]
    (.mkdirs (io/file content))
    (.mkdirs (io/file reviews))
    (binding [*config* {:store-dir content :review-dir reviews}]
      (try
        (f)
        (finally
          ;; Clean up both directories
          (doseq [dir [content reviews]]
            (let [d (io/file dir)]
              (when (.exists d)
                (doseq [f (.listFiles d)]
                  (.delete f))
                (.delete d))))
          (.delete (io/file base)))))))

(use-fixtures :each temp-dirs-fixture)

;; --- URL ingestion: end-to-end ---

(deftest ingest-url-end-to-end-test
  (testing "ingests content from a URL-only payload"
    (let [result (app/ingest-content!
                   (merge *config*
                          {:url "https://clojure.org/about/rationale"}))]
      (is (:success result)
          "Should successfully ingest from URL")
      (is (some? (:content result))
          "Should return the processed content")
      (is (string? (get-in result [:content :source-text]))
          "Should have extracted text")
      (is (> (count (get-in result [:content :source-text])) 50)
          "Extracted text should be substantial")
      (is (= "https://clojure.org/about/rationale"
             (get-in result [:content :url]))
          "Should preserve the source URL")
      (is (some? (:review result))
          "Should create an initial review")
      (is (m/validate schemas/Content (:content result))
          "Content should conform to schema")))

  (testing "preserves explicit title over fetched title"
    ;; Use a different URL than the first test to avoid duplicate detection
    (let [result (app/ingest-content!
                   (merge *config*
                          {:url   "https://clojure.org/about/state"
                           :title "My Custom Title"}))]
      (is (:success result))
      (is (not (:existing result))
          "Should not be flagged as existing (different URL)")
      (is (= "My Custom Title" (get-in result [:content :title]))
          "Explicit title should override the fetched one"))))

;; --- URL ingestion: text takes precedence ---

(deftest ingest-text-precedence-test
  (testing "uses provided :text when both :text and :url are given"
    (let [result (app/ingest-content!
                   (merge *config*
                          {:text "My manually pasted content about Clojure."
                           :url  "https://clojure.org/about/rationale"}))]
      (is (:success result))
      (is (= "My manually pasted content about Clojure."
             (get-in result [:content :source-text]))
          ":text should win when both are provided"))))

;; --- Duplicate URL detection ---

(deftest duplicate-url-detection-test
  (testing "first ingest creates new content"
    (let [result1 (app/ingest-content!
                    (merge *config*
                           {:url "https://clojure.org/about/rationale"}))]
      (is (:success result1))
      (is (nil? (:existing result1))
          "First ingest should not be marked as existing")

      (testing "second ingest of same URL returns existing content"
        (let [result2 (app/ingest-content!
                        (merge *config*
                               {:url "https://clojure.org/about/rationale"}))]
          (is (:success result2)
              "Should still succeed")
          (is (true? (:existing result2))
              "Should be flagged as existing")
          (is (= (get-in result1 [:content :id])
                 (get-in result2 [:content :id]))
              "Should return the same content entity"))))))

;; --- URL ingestion: error cases ---

(deftest ingest-url-error-test
  (testing "returns failure for unreachable URL"
    (let [result (app/ingest-content!
                   (merge *config*
                          {:url "https://this-domain-definitely-does-not-exist-xyz123.com"}))]
      (is (not (:success result)))
      (is (string? (:reason result)))))

  (testing "returns failure for invalid URL"
    (let [result (app/ingest-content!
                   (merge *config*
                          {:url "not-a-url"}))]
      (is (not (:success result)))
      (is (string? (:reason result))))))

;; --- Metadata passthrough ---

(deftest ingest-url-metadata-test
  (testing "passes through metadata from URL fetching to Content entity"
    (let [result (app/ingest-content!
                   (merge *config*
                          {:url "https://clojure.org/about/rationale"}))]
      (is (:success result))
      ;; Metadata may or may not be present depending on the page's OG tags.
      ;; We verify that IF present, it conforms to the schema.
      (when-let [meta (get-in result [:content :meta])]
        (is (map? meta)
            "Metadata should be a map")
        (is (m/validate schemas/ContentMeta meta)
            "Metadata should conform to ContentMeta schema")))))
