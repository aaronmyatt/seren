(ns fcis.app.islands.login
  "Login page island — attaches form submission to PocketBase auth.

   Finds the server-rendered form via [data-island='login-form'],
   intercepts submit, calls PocketBase authWithPassword, and
   redirects to /dashboard on success.

   See: https://pocketbase.io/docs/authentication/#authenticate-with-password"
  (:require [fcis.app.islands.shared :as shared]))

(defn- show-error!
  "Shows an error message in the login-error island element."
  [message]
  (when-let [el (shared/find-island "login-error")]
    (set! (.-textContent el) message)
    (.removeAttribute el "hidden")))

(defn- hide-error!
  "Hides the login-error island element."
  []
  (when-let [el (shared/find-island "login-error")]
    (.setAttribute el "hidden" "")))

(defn- get-input-value
  "Gets the value from a Shoelace sl-input inside a parent element.
   Shoelace inputs store their value as a property, not an attribute."
  [parent-el input-name]
  (when-let [input (.querySelector parent-el (str "[name=\"" input-name "\"]"))]
    (.-value input)))

(defn- handle-login!
  "Handles login form submission.
   Calls PocketBase authWithPassword, redirects on success, shows error on failure."
  [form-el]
  (let [email      (get-input-value form-el "email")
        password   (get-input-value form-el "password")
        ^js client (shared/pb)]
    (hide-error!)
    ;; PocketBase SDK: pb.collection('users').authWithPassword(email, password)
    ;; Returns a Promise. On success, token is stored in localStorage automatically.
    ;; See: https://github.com/pocketbase/js-sdk#authenticate-as-auth-record
    (-> (.authWithPassword (.collection client "users") email password)
        (.then (fn [_auth-data]
                 (set! js/window.location "/dashboard")))
        (.catch (fn [err]
                  (js/console.error "Login failed:" err)
                  (show-error! (or (.-message err) "Login failed. Check your credentials.")))))))

(defn ^:export init
  "Mounts the login island. Finds the form and attaches submit behavior."
  []
  ;; If already authenticated, skip login and go to dashboard
  (if (shared/authenticated?)
    (set! js/window.location "/dashboard")
    ;; Attach form submit handler
    (when-let [form (shared/find-island "login-form")]
      (.addEventListener form "submit"
        (fn [e]
          (.preventDefault e)
          (handle-login! form))))))

;; Initialize on load
(init)
