(ns seren.adapter.review-store
  "JVM adapter for review persistence — file-based storage using EDN.

   Each review is stored as an EDN file in a directory, keyed by :id.
   Follows the same pattern as content-store: one .edn file per entity,
   store-dir as the first argument (no globals).

   Reviews track SM-2 scheduling state — interval, ease-factor, repetitions —
   for each content item. The dashboard queries due reviews to show the
   user what to practice next.

   See: https://clojure.org/reference/reader#_tagged_literals"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [malli.core :as m]
            [seren.core.schemas :as schemas]
            [seren.schemas.common :as common]))

(defn save-review!
  "Persists a Review entity to disk as an EDN file.
   Creates the store directory if it doesn't exist."
  [store-dir review]
  (let [dir (io/file store-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))
    (let [f (io/file dir (str (:id review) ".edn"))]
      (spit f (pr-str review))
      review)))

(m/=> save-review! [:=> [:cat :string schemas/Review] schemas/Review])

(defn load-review
  "Loads a single Review entity by ID. Returns nil if not found."
  [store-dir review-id]
  (let [f (io/file store-dir (str review-id ".edn"))]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(m/=> load-review [:=> [:cat :string common/Id] [:maybe schemas/Review]])

(defn list-reviews
  "Returns a vector of all stored Review entities, sorted by due-at ascending
   (most urgent first)."
  [store-dir]
  (let [dir (io/file store-dir)]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(.. % getName (endsWith ".edn")))
           (mapv #(edn/read-string (slurp %)))
           (sort-by :due-at)
           vec)
      [])))

(m/=> list-reviews [:=> [:cat :string] [:vector schemas/Review]])

(defn find-reviews-by-content
  "Returns all reviews for a given content ID, sorted by due-at ascending."
  [store-dir content-id]
  (->> (list-reviews store-dir)
       (filter #(= content-id (:content-id %)))
       vec))

(m/=> find-reviews-by-content [:=> [:cat :string common/Id] [:vector schemas/Review]])

(defn list-due-reviews
  "Returns reviews that are due for practice (due-at <= now-ms),
   excluding completed ones. Sorted by due-at ascending (most overdue first)."
  [store-dir now-ms]
  (->> (list-reviews store-dir)
       (filter #(and (<= (:due-at %) now-ms)
                     (not= :completed (:status %))))
       vec))

(m/=> list-due-reviews [:=> [:cat :string common/Timestamp] [:vector schemas/Review]])

(defn delete-review!
  "Deletes a Review entity by ID. Returns true if deleted, false if not found."
  [store-dir review-id]
  (let [f (io/file store-dir (str review-id ".edn"))]
    (if (.exists f)
      (.delete f)
      false)))

(m/=> delete-review! [:=> [:cat :string common/Id] :boolean])

(comment
  ;; Save a review
  (save-review! "/tmp/seren-reviews"
                {:id "rev-1" :content-id "c-1" :status :pending
                 :shape :headings-only :interval 0 :ease-factor 2.5
                 :repetitions 0 :quality nil
                 :due-at 1710000000000 :created-at 1710000000000
                 :reviewed-at nil})

  ;; List all reviews (most urgent first)
  (list-reviews "/tmp/seren-reviews")

  ;; Find reviews due now
  (list-due-reviews "/tmp/seren-reviews" (System/currentTimeMillis)))
