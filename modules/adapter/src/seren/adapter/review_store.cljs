(ns seren.adapter.review-store
  "ClojureScript adapter for review persistence — in-memory atom storage.

   Mirrors the JVM file-based adapter with the same public API.
   Used in the browser where reviews are fetched via the server API,
   but a local cache is maintained for responsiveness.

   The store-key parameter (string) partitions different stores,
   matching the JVM adapter's store-dir concept.

   See: https://clojure.org/reference/atoms"
  (:require [malli.core :as m]
            [seren.core.schemas :as schemas]
            [seren.schemas.common :as common]))

;; Atom-backed storage — a map of store-key → {id → Review}
;; See: https://clojurescript.org/reference/atoms
(defonce ^:private stores (atom {}))

(defn save-review!
  "Persists a Review entity to the in-memory store."
  [store-key review]
  (swap! stores assoc-in [store-key (:id review)] review)
  review)

(m/=> save-review! [:=> [:cat :string schemas/Review] schemas/Review])

(defn load-review
  "Loads a single Review entity by ID. Returns nil if not found."
  [store-key review-id]
  (get-in @stores [store-key review-id]))

(m/=> load-review [:=> [:cat :string common/Id] [:maybe schemas/Review]])

(defn list-reviews
  "Returns a vector of all stored Review entities, sorted by due-at ascending."
  [store-key]
  (->> (vals (get @stores store-key {}))
       (sort-by :due-at)
       vec))

(m/=> list-reviews [:=> [:cat :string] [:vector schemas/Review]])

(defn find-reviews-by-content
  "Returns all reviews for a given content ID, sorted by due-at ascending."
  [store-key content-id]
  (->> (list-reviews store-key)
       (filter #(= content-id (:content-id %)))
       vec))

(m/=> find-reviews-by-content [:=> [:cat :string common/Id] [:vector schemas/Review]])

(defn list-due-reviews
  "Returns reviews that are due for practice (due-at <= now-ms),
   excluding completed ones. Sorted by due-at ascending."
  [store-key now-ms]
  (->> (list-reviews store-key)
       (filter #(and (<= (:due-at %) now-ms)
                     (not= :completed (:status %))))
       vec))

(m/=> list-due-reviews [:=> [:cat :string common/Timestamp] [:vector schemas/Review]])

(defn delete-review!
  "Deletes a Review entity by ID. Returns true if the key existed."
  [store-key review-id]
  (let [existed? (contains? (get @stores store-key) review-id)]
    (swap! stores update store-key dissoc review-id)
    existed?))

(m/=> delete-review! [:=> [:cat :string common/Id] :boolean])

(defn clear-store!
  "Clears all reviews in a store. Useful for testing."
  [store-key]
  (swap! stores dissoc store-key)
  nil)

(comment
  (save-review! "test"
                {:id "rev-1" :content-id "c-1" :status :pending
                 :scaffold :none :interval 0 :ease-factor 2.5
                 :repetitions 0 :quality nil
                 :due-at 1710000000000 :created-at 1710000000000
                 :reviewed-at nil})
  (list-reviews "test")
  (list-due-reviews "test" 1710000000001)
  (clear-store! "test"))
