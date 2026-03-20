(ns seren.adapter.content-store-test
  "Tests for content store — focusing on find-by-url for duplicate detection.

   Uses a temporary directory for each test to avoid state leakage.

   See: https://clojure.org/guides/test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [seren.adapter.content-store :as store]))

;; --- Test fixtures ---
;; Each test gets a fresh temp directory that's cleaned up afterward.
;; See: https://clojure.org/guides/test#_fixtures

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/seren-test-" (System/nanoTime))]
    (binding [*test-dir* dir]
      (try
        (f)
        (finally
          ;; Clean up
          (let [d (io/file dir)]
            (when (.exists d)
              (doseq [f (.listFiles d)]
                (.delete f))
              (.delete d))))))))

(use-fixtures :each temp-dir-fixture)

;; --- Test data ---

(defn- make-content
  "Creates a minimal Content entity for testing."
  [id & {:keys [url title] :or {title "Test" url nil}}]
  {:id          id
   :url         url
   :title       title
   :source-text "Some article text for testing."
   :chunks      [{:index 0 :text "Some article text for testing."}]
   :headings    []
   :summary     "Some article text for testing."
   :tags        ["test"]
   :created-at  (System/currentTimeMillis)})

;; --- find-by-url tests ---

(deftest find-by-url-test
  (testing "returns nil when store is empty"
    (is (nil? (store/find-by-url *test-dir* "https://example.com/article"))))

  (testing "returns nil when no content matches the URL"
    (store/save-content! *test-dir*
                         (make-content "id-1" :url "https://other.com/page"))
    (is (nil? (store/find-by-url *test-dir* "https://example.com/article"))))

  (testing "finds content by matching URL"
    (let [content (make-content "id-2" :url "https://example.com/article"
                                :title "Found Article")]
      (store/save-content! *test-dir* content)
      (let [found (store/find-by-url *test-dir* "https://example.com/article")]
        (is (some? found)
            "Should find content with matching URL")
        (is (= "id-2" (:id found)))
        (is (= "Found Article" (:title found))))))

  (testing "returns nil for blank URL"
    (store/save-content! *test-dir*
                         (make-content "id-3" :url "https://example.com"))
    (is (nil? (store/find-by-url *test-dir* "")))
    (is (nil? (store/find-by-url *test-dir* "   ")))
    (is (nil? (store/find-by-url *test-dir* nil))))

  (testing "returns nil when content has no URL (pasted text)"
    (store/save-content! *test-dir*
                         (make-content "id-4" :url nil :title "Pasted Text"))
    (is (nil? (store/find-by-url *test-dir* "https://example.com/missing")))))
