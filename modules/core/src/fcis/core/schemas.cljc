(ns fcis.core.schemas
  "Domain schemas for the Core module.
   These define the shape of data flowing through pure functions."
  (:require [fcis.schemas.common :as common]))

;; --- User domain schemas ---

(def UserName
  "A valid user name."
  [:string {:min 1 :max 100}])

(def User
  "A complete user entity."
  [:map
   [:id common/Id]
   [:name UserName]
   [:email common/Email]
   [:created-at common/Timestamp]])

(def UserInput
  "Raw user input before validation."
  [:map
   [:name :string]
   [:email :string]])
