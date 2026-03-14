(ns fcis.adapter.user-store
  "In-memory user persistence for ClojureScript.
   Uses an atom as the backing store — works in both Node.js and browser.
   Same public API as the JVM (.clj) version, same Malli schemas.

   The first argument to each function is a store-key (string) that
   identifies which store to use. On the JVM this is a directory path;
   here it's a key in an atom. The Core and App layers don't care
   about the difference — that's the point of the Adapter pattern."
  (:require [malli.core :as m]
            [fcis.core.schemas :as schemas]
            [fcis.schemas.common :as common]))

;; In-memory store: {store-key {user-id user-map}}
(defonce ^:private stores (atom {}))

(defn save-user!
  "Persists a user to the in-memory store.
   Creates the store if it doesn't exist.
   Returns the user map."
  [store-key user]
  (swap! stores assoc-in [store-key (:id user)] user)
  user)

(m/=> save-user! [:=> [:cat :string schemas/User] schemas/User])

(defn load-user
  "Loads a user by ID from the in-memory store.
   Returns the user map, or nil if not found."
  [store-key user-id]
  (get-in @stores [store-key user-id]))

(m/=> load-user [:=> [:cat :string common/Id] [:maybe schemas/User]])

(defn list-users
  "Lists all users in the store.
   Returns a vector of user maps."
  [store-key]
  (vec (vals (get @stores store-key {}))))

(m/=> list-users [:=> [:cat :string] [:vector schemas/User]])

(defn delete-user!
  "Deletes a user from the in-memory store.
   Returns true if the user existed, false otherwise."
  [store-key user-id]
  (let [existed? (contains? (get @stores store-key) user-id)]
    (swap! stores update store-key dissoc user-id)
    existed?))

(m/=> delete-user! [:=> [:cat :string common/Id] :boolean])

(defn clear-store!
  "Clears all users from a store. Useful for testing."
  [store-key]
  (swap! stores dissoc store-key)
  nil)

;; ---- Browser Console Examples ----
;; Open browser devtools console after loading the app:
;;
;;   fcis.adapter.user_store.save_user_BANG_("demo", {id: "1", name: "Alice", ...})
;;   fcis.adapter.user_store.list_users("demo")
;;
;; Or from a ClojureScript REPL:
(comment
  (save-user! "demo" {:id "abc-123"
                       :name "Alice"
                       :email "alice@example.com"
                       :created-at 1709568000000})

  (load-user "demo" "abc-123")
  ;; => {:id "abc-123", :name "Alice", ...}

  (list-users "demo")
  ;; => [{:id "abc-123", ...}]

  (delete-user! "demo" "abc-123")
  ;; => true

  (clear-store! "demo")
  )
