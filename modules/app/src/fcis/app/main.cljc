(ns fcis.app.main
  "Application layer — wires Core and Adapter together.
   Orchestrates user workflows by calling pure functions (Core)
   and side-effectful operations (Adapter)."
  (:require [malli.core :as m]
            [fcis.core.user :as user]
            [fcis.core.schemas :as schemas]
            [fcis.adapter.user-store :as store]
            [fcis.schemas.common :as common]))

(def AppConfig
  "Configuration map for the application."
  [:map
   [:store-dir :string]])

(def RegistrationInput
  "Input for user registration."
  [:map
   [:store-dir :string]
   [:name :string]
   [:email :string]])

(def RegistrationResult
  "Result of a user registration attempt."
  [:or
   [:map
    [:success :boolean]
    [:user schemas/User]]
   [:map
    [:success :boolean]
    [:errors [:vector :string]]]])

(defn register-user!
  "Registers a new user: validates input, creates user, persists to store.
   Returns {:success true :user ...} or {:success false :errors [...]}."
  [input]
  (let [validation (user/validate-user-input {:name  (:name input)
                                               :email (:email input)})]
    (if (:valid? validation)
      (let [new-user (user/create-user (:name input) (:email input))]
        (store/save-user! (:store-dir input) new-user)
        {:success true :user new-user})
      {:success false :errors (:errors validation)})))

(m/=> register-user! [:=> [:cat RegistrationInput] RegistrationResult])

(defn get-user
  "Retrieves a user by ID from the store."
  [config user-id]
  (store/load-user (:store-dir config) user-id))

(m/=> get-user [:=> [:cat AppConfig common/Id] [:maybe schemas/User]])

(defn list-all-users
  "Lists all users in the store."
  [config]
  (store/list-users (:store-dir config)))

(m/=> list-all-users [:=> [:cat AppConfig] [:vector schemas/User]])

;; ---- REPL Examples ----
(comment
  (def config {:store-dir "/tmp/fcis-app-test"})

  ;; Register a user (happy path)
  (register-user! {:store-dir "/tmp/fcis-app-test"
                    :name "Alice"
                    :email "alice@example.com"})
  ;; => {:success true, :user {:id "...", :name "Alice", ...}}

  ;; Register with invalid input
  (register-user! {:store-dir "/tmp/fcis-app-test"
                    :name ""
                    :email "bad"})
  ;; => {:success false, :errors ["Name cannot be blank" "Invalid email format"]}

  ;; List all users
  (list-all-users config)

  ;; Get a specific user
  (get-user config "some-user-id")
  )
