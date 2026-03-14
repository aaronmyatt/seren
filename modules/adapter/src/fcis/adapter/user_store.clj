(ns fcis.adapter.user-store
  "File-based user persistence.
   Side-effectful — reads and writes EDN files to disk.
   Depends on fcis.core for schemas and data transformations."
  (:require [malli.core :as m]
            [fcis.core.schemas :as schemas]
            [fcis.schemas.common :as common]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- user-file
  "Returns the File for a given user ID within the store directory."
  [store-dir user-id]
  (io/file store-dir (str user-id ".edn")))

(defn save-user!
  "Persists a user map to disk as an EDN file.
   Creates the store directory if it doesn't exist.
   Returns the user map."
  [store-dir user]
  (.mkdirs (io/file store-dir))
  (spit (user-file store-dir (:id user))
        (pr-str user))
  user)

(m/=> save-user! [:=> [:cat :string schemas/User] schemas/User])

(defn load-user
  "Loads a user by ID from the store directory.
   Returns the user map, or nil if not found."
  [store-dir user-id]
  (let [f (user-file store-dir user-id)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(m/=> load-user [:=> [:cat :string common/Id] [:maybe schemas/User]])

(defn list-users
  "Lists all users in the store directory.
   Returns a vector of user maps."
  [store-dir]
  (let [dir (io/file store-dir)]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".edn"))
           (mapv #(edn/read-string (slurp %))))
      [])))

(m/=> list-users [:=> [:cat :string] [:vector schemas/User]])

(defn delete-user!
  "Deletes a user file from the store directory.
   Returns true if the file was deleted, false otherwise."
  [store-dir user-id]
  (let [f (user-file store-dir user-id)]
    (.delete f)))

(m/=> delete-user! [:=> [:cat :string common/Id] :boolean])

;; ---- REPL Examples ----
(comment
  (def test-dir "/tmp/fcis-repl-test")

  ;; Save a user
  (save-user! test-dir {:id "abc-123"
                         :name "Alice"
                         :email "alice@example.com"
                         :created-at 1709568000000})

  ;; Load it back
  (load-user test-dir "abc-123")
  ;; => {:id "abc-123", :name "Alice", :email "alice@example.com", :created-at 1709568000000}

  ;; List all users
  (list-users test-dir)
  ;; => [{:id "abc-123", ...}]

  ;; Delete
  (delete-user! test-dir "abc-123")
  ;; => true
  )
