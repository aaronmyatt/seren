(ns fcis.app.pages.login
  "Login page — server-rendered form with CLJS island for PocketBase auth.

   The form HTML is fully rendered server-side. The CLJS island in
   fcis.app.islands.login attaches submit behavior using data-island
   attributes. The page degrades gracefully without JS (form posts
   nowhere, but structure is visible).

   See: https://shoelace.style/components/input
   See: https://pocketbase.io/docs/authentication/"
  (:require [fcis.app.pages.layout :as layout]
            [ring.util.response :as response]))

(defn handler
  "Ring handler for GET /login. Renders the login form page."
  [_request]
  (-> (layout/page
        {:title "Sign In — FCIS"
         :modules [:shared :login]
         :body
         [:main.page-login
          [:div.login-card
           [:h1 "Sign In"]
           ;; data-island="login-form" — CLJS mount point for form behavior
           [:form {:data-island "login-form"
                   :method "POST"
                   :action "#"}
            [:sl-input {:name "email"
                        :type "email"
                        :label "Email"
                        :placeholder "you@example.com"
                        :required true}]
            [:sl-input {:name "password"
                        :type "password"
                        :label "Password"
                        :password-toggle true
                        :required true}]
            [:div.form-actions
             [:sl-button {:type "submit"
                          :variant "primary"
                          :size "large"}
              "Sign In"]]
            ;; Error display — hidden by default, shown by CLJS on auth failure
            [:div.error-message {:data-island "login-error"
                                 :hidden true}]]]]})
      (response/response)
      (response/content-type "text/html")))

(comment
  ;; Test the handler
  (handler {})

  ;; View raw HTML output
  (println (:body (handler {}))))
