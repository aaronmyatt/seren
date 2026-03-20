(ns seren.adapter.content-store
  "JVM adapter for content persistence — file-based storage using EDN.

   Each content item is stored as an EDN file in a directory, keyed by :id.
   This mirrors the template's user-store pattern. In production, this
   will be backed by PocketBase via HTTP; the file-based version is
   useful for development and testing without PocketBase running.

   All functions take the store directory as the first argument —
   no globals, following the FCIS adapter convention.

   See: https://clojure.org/reference/reader#_tagged_literals"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [seren.core.schemas :as schemas]
            [seren.schemas.common :as common]))

(defn save-content!
  "Persists a Content entity to disk as an EDN file.
   Creates the store directory if it doesn't exist."
  [store-dir content]
  (let [dir (io/file store-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))
    (let [f (io/file dir (str (:id content) ".edn"))]
      (spit f (pr-str content))
      content)))

(m/=> save-content! [:=> [:cat :string schemas/Content] schemas/Content])

(defn load-content
  "Loads a single Content entity by ID. Returns nil if not found."
  [store-dir content-id]
  (let [f (io/file store-dir (str content-id ".edn"))]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(m/=> load-content [:=> [:cat :string common/Id] [:maybe schemas/Content]])

(defn list-contents
  "Returns a vector of all stored Content entities, sorted newest first."
  [store-dir]
  (let [dir (io/file store-dir)]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(.. % getName (endsWith ".edn")))
           (mapv #(edn/read-string (slurp %)))
           (sort-by :created-at >)
           vec)
      [])))

(m/=> list-contents [:=> [:cat :string] [:vector schemas/Content]])

(defn find-by-url
  "Finds a Content entity by its :url field. Returns the first match or nil.
   Used for duplicate detection — a repeated URL is a learning signal,
   not an error. See plan-url-fetching.md § 'Duplicate URLs'."
  [store-dir url]
  (when-not (str/blank? url)
    (let [dir (io/file store-dir)]
      (when (.exists dir)
        (->> (.listFiles dir)
             (filter #(.. % getName (endsWith ".edn")))
             (some (fn [f]
                     (let [content (edn/read-string (slurp f))]
                       (when (= url (:url content))
                         content)))))))))

(m/=> find-by-url [:=> [:cat :string [:maybe :string]] [:maybe schemas/Content]])

(defn delete-content!
  "Deletes a Content entity by ID. Returns true if deleted, false if not found."
  [store-dir content-id]
  (let [f (io/file store-dir (str content-id ".edn"))]
    (if (.exists f)
      (.delete f)
      false)))

(m/=> delete-content! [:=> [:cat :string common/Id] :boolean])

(comment
  ;; Save content
  (save-content! "/tmp/seren-content"
                 {:id "test-1" :title "Test" :source-text "Hello"
                  :chunks [] :headings [] :summary "Hello" :tags []
                  :created-at 1710000000000})

  ;; List all content
  (list-contents "/tmp/seren-content")

  ;; Load by ID
  (load-content "/tmp/seren-content" "test-1")

  ;; Find by URL (duplicate detection)
  (find-by-url "/tmp/seren-content" "https://example.com/article"))
