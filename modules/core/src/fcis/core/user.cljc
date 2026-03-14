(ns fcis.core.user
  "Pure functions for user domain logic.
   No side effects — all functions take data and return data."
  (:require [malli.core :as m]
            [fcis.core.schemas :as schemas]
            [fcis.schemas.common :as common]
            [clojure.string :as str]))

(defn normalize-email
  "Lowercases and trims an email address."
  [email]
  (-> email str/trim str/lower-case))

(m/=> normalize-email [:=> [:cat :string] :string])

(defn validate-email
  "Checks if an email address is valid.
   Returns a validation result map."
  [email]
  (let [valid? (boolean (re-matches #".+@.+\..+" email))]
    {:valid? valid?
     :reason (when-not valid? "Invalid email format")}))

(m/=> validate-email [:=> [:cat :string] common/ValidationResult])

(defn validate-name
  "Checks if a user name is valid (non-blank, within length limits).
   Returns a validation result map."
  [name]
  (cond
    (str/blank? name)
    {:valid? false :reason "Name cannot be blank"}

    (> (count name) 100)
    {:valid? false :reason "Name cannot exceed 100 characters"}

    :else
    {:valid? true :reason nil}))

(m/=> validate-name [:=> [:cat :string] common/ValidationResult])

(defn validate-user-input
  "Validates a complete user input map.
   Returns aggregated validation results."
  [input]
  (let [name-result  (validate-name (:name input ""))
        email-result (validate-email (:email input ""))
        errors       (cond-> []
                       (not (:valid? name-result))
                       (conj (:reason name-result))

                       (not (:valid? email-result))
                       (conj (:reason email-result)))]
    {:valid? (empty? errors)
     :errors errors}))

(m/=> validate-user-input [:=> [:cat schemas/UserInput] common/ValidationResults])

(defn create-user
  "Creates a user map from a name and email.
   Pure data transformation — assigns an ID and timestamp."
  [name email]
  {:id         (str (random-uuid))
   :name       name
   :email      (normalize-email email)
   :created-at #?(:clj  (inst-ms (java.util.Date.))
                  :cljs (.getTime (js/Date.)))})

(m/=> create-user [:=> [:cat :string :string] schemas/User])

;; ---- REPL Examples ----
(comment
  (normalize-email "  Alice@Example.COM  ")
  ;; => "alice@example.com"

  (validate-email "alice@example.com")
  ;; => {:valid? true, :reason nil}

  (validate-email "not-an-email")
  ;; => {:valid? false, :reason "Invalid email format"}

  (validate-name "Alice")
  ;; => {:valid? true, :reason nil}

  (validate-name "")
  ;; => {:valid? false, :reason "Name cannot be blank"}

  (validate-user-input {:name "Alice" :email "alice@example.com"})
  ;; => {:valid? true, :errors []}

  (validate-user-input {:name "" :email "bad"})
  ;; => {:valid? false, :errors ["Name cannot be blank" "Invalid email format"]}

  (create-user "Alice" "alice@example.com")
  ;; => {:id "..." :name "Alice" :email "alice@example.com" :created-at ...}
  )
