(ns fcis.app.islands.dashboard
  "Dashboard page island — auth guard, user display, and logout.

   Checks PocketBase auth state on load. If not authenticated,
   redirects to /login. Otherwise populates user info and
   attaches the logout button.

   See: https://pocketbase.io/docs/authentication/"
  (:require [fcis.app.islands.shared :as shared]))

(defn- populate-user-display!
  "Fills in the user-display island with the current user's email."
  []
  (when-let [el (shared/find-island "user-display")]
    (if-let [user (shared/current-user)]
      (set! (.-textContent el)
            (or (.-email user) (.-name user) "User"))
      (set! (.-textContent el) "Unknown"))))

(defn- attach-logout!
  "Attaches click handler to the logout-button island."
  []
  (when-let [btn (shared/find-island "logout-button")]
    (.addEventListener btn "click"
      (fn [_e]
        (shared/logout!)
        (set! js/window.location "/login")))))

(defn ^:export init
  "Mounts the dashboard island. Checks auth, populates user info, wires logout."
  []
  ;; Auth guard — redirect if not authenticated
  (if-not (shared/authenticated?)
    (set! js/window.location "/login")
    (do
      (populate-user-display!)
      (attach-logout!))))

;; Initialize on load
(init)
