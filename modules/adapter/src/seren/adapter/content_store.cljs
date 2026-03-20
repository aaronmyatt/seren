(ns seren.adapter.content-store
  "ClojureScript adapter for content persistence — in-memory atom storage.

   Mirrors the JVM file-based adapter with the same public API.
   Used in the browser where content is fetched via the server API,
   but a local cache is maintained for responsiveness.

   The store-key parameter (string) partitions different stores,
   matching the JVM adapter's store-dir concept.

   See: https://clojure.org/reference/atoms"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [seren.core.schemas :as schemas]
            [seren.schemas.common :as common]))

;; Atom-backed storage — a map of store-key → {id → Content}
;; See: https://clojurescript.org/reference/atoms
(defonce ^:private stores (atom {}))

(defn save-content!
  "Persists a Content entity to the in-memory store."
  [store-key content]
  (swap! stores assoc-in [store-key (:id content)] content)
  content)

(m/=> save-content! [:=> [:cat :string schemas/Content] schemas/Content])

(defn load-content
  "Loads a single Content entity by ID. Returns nil if not found."
  [store-key content-id]
  (get-in @stores [store-key content-id]))

(m/=> load-content [:=> [:cat :string common/Id] [:maybe schemas/Content]])

(defn list-contents
  "Returns a vector of all stored Content entities, sorted newest first."
  [store-key]
  (->> (vals (get @stores store-key {}))
       (sort-by :created-at >)
       vec))

(m/=> list-contents [:=> [:cat :string] [:vector schemas/Content]])

(defn find-by-url
  "Finds a Content entity by its :url field. Returns the first match or nil.
   Used for duplicate detection — a repeated URL is a learning signal,
   not an error. See plan-url-fetching.md § 'Duplicate URLs'."
  [store-key url]
  (when-not (str/blank? url)
    (->> (vals (get @stores store-key {}))
         (some (fn [content]
                 (when (= url (:url content))
                   content))))))

(m/=> find-by-url [:=> [:cat :string [:maybe :string]] [:maybe schemas/Content]])

(defn delete-content!
  "Deletes a Content entity by ID. Returns true if the key existed."
  [store-key content-id]
  (let [existed? (contains? (get @stores store-key) content-id)]
    (swap! stores update store-key dissoc content-id)
    existed?))

(m/=> delete-content! [:=> [:cat :string common/Id] :boolean])

(defn clear-store!
  "Clears all content in a store. Useful for testing."
  [store-key]
  (swap! stores dissoc store-key)
  nil)

(comment
  (save-content! "test"
                 {:id "1" :title "Test" :source-text "Hello"
                  :chunks [] :headings [] :summary "Hello" :tags []
                  :created-at 1710000000000})
  (list-contents "test")
  (load-content "test" "1")
  (clear-store! "test")

  ;; Find by URL (duplicate detection)
  (find-by-url "test" "https://example.com/article"))
