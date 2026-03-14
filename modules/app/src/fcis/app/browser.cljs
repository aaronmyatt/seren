(ns fcis.app.browser
  "DEPRECATED: Replaced by per-page island modules in fcis.app.islands.*.
   The MPA architecture uses module splitting — each page loads its own
   CLJS module. See shadow-cljs.edn :browser :modules.

   This file is kept for reference. It is no longer loaded by shadow-cljs.

   ---
   Original: Browser entry point demonstrating the full FCIS stack.
   Open the browser console to see the output."
  (:require [fcis.app.main :as app]
            [fcis.core.user :as user]
            [fcis.adapter.user-store :as store]))

(defn demo-validation
  "Demonstrates Core layer validation — pure functions, same on JVM and JS."
  []
  (js/console.group "🔍 Core Layer: Validation (pure functions)")

  (js/console.log "Valid email:"
                  (clj->js (user/validate-email "alice@example.com")))

  (js/console.log "Invalid email:"
                  (clj->js (user/validate-email "not-an-email")))

  (js/console.log "Valid user input:"
                  (clj->js (user/validate-user-input
                             {:name "Alice" :email "alice@example.com"})))

  (js/console.log "Invalid user input:"
                  (clj->js (user/validate-user-input
                             {:name "" :email "bad"})))
  (js/console.groupEnd))

(defn demo-full-stack
  "Demonstrates the full Core → Adapter → App flow in the browser."
  []
  (js/console.group "🚀 Full Stack: Register → List → Retrieve")

  ;; Register two users through the App layer
  (let [result1 (app/register-user! {:store-dir "browser-demo"
                                      :name "Alice"
                                      :email "alice@example.com"})
        result2 (app/register-user! {:store-dir "browser-demo"
                                      :name "Bob"
                                      :email "bob@example.com"})]

    (js/console.log "Registered Alice:" (clj->js result1))
    (js/console.log "Registered Bob:" (clj->js result2))

    ;; List all users
    (let [all-users (app/list-all-users {:store-dir "browser-demo"})]
      (js/console.log (str "All users (" (count all-users) "):") (clj->js all-users)))

    ;; Retrieve a specific user
    (when-let [alice-id (get-in result1 [:user :id])]
      (let [loaded (app/get-user {:store-dir "browser-demo"} alice-id)]
        (js/console.log "Retrieved Alice by ID:" (clj->js loaded)))))

  ;; Demonstrate validation failure through App layer
  (let [bad-result (app/register-user! {:store-dir "browser-demo"
                                         :name ""
                                         :email "bad"})]
    (js/console.log "Invalid registration:" (clj->js bad-result)))

  ;; Clean up
  (store/clear-store! "browser-demo")
  (js/console.groupEnd))

(defn ^:export init
  "Called by shadow-cljs on page load. Runs the demo."
  []
  (js/console.log "=== FCIS Browser Demo ===")
  (js/console.log "Same pure Core logic, different Adapter (in-memory vs files)")
  (js/console.log "")
  (demo-validation)
  (js/console.log "")
  (demo-full-stack)
  (js/console.log "")
  (js/console.log "✅ Done! The same functions run on JVM and in the browser."))
