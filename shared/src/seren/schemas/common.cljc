(ns seren.schemas.common
  "Cross-cutting schemas shared across all modules.
   Domain-specific schemas belong in their respective module's schemas.clj.")

;; --- Primitive types with constraints ---

(def NonBlankString
  "A string that is not empty or whitespace-only."
  [:string {:min 1}])

(def PosInt
  "A positive integer."
  [:int {:min 1}])

;; --- Common domain types ---

(def Id
  "A string identifier (typically UUID)."
  [:string {:min 1}])

(def Timestamp
  "Unix timestamp in milliseconds."
  [:int {:min 0}])

(def Email
  "An email address string."
  [:and
   :string
   [:re #".+@.+\..+"]])

;; --- Result types ---

(def ValidationResult
  "Standard result map for validation functions."
  [:map
   [:valid? :boolean]
   [:reason {:optional true} [:maybe :string]]])

(def ValidationResults
  "Aggregated validation results with multiple potential errors."
  [:map
   [:valid? :boolean]
   [:errors [:vector :string]]])
