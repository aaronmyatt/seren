(ns fcis.app.pages.dashboard
  "Dashboard page — shows authenticated user info.

   Server renders the page shell with skeleton loading states.
   The CLJS island in fcis.app.islands.dashboard checks auth
   (redirects to /login if not authenticated) and populates
   user data from the PocketBase auth store.

   See: https://shoelace.style/components/skeleton"
  (:require [fcis.app.pages.layout :as layout]
            [ring.util.response :as response]))

(defn handler
  "Ring handler for GET /dashboard. Renders the dashboard page shell."
  [_request]
  (-> (layout/page
        {:title "Dashboard — FCIS"
         :modules [:shared :dashboard]
         :body
         [:main.page-dashboard
          ;; Auth guard — CLJS redirects to /login if no valid token
          [:div {:data-island "auth-guard"}]

          [:header
           [:h1 "Dashboard"]
           [:div.user-info
            ;; CLJS populates with user email from PocketBase auth store
            [:span {:data-island "user-display"}
             [:sl-skeleton {:effect "sheen"
                            :style "width: 200px; height: 1.2em;"}]]]
           [:sl-button {:data-island "logout-button"
                        :variant "text"
                        :size "small"}
            "Sign Out"]]

          [:section.dashboard-content
           [:p "Welcome to the FCIS dashboard."]
           [:p "This page is protected — only visible to authenticated users."]]]})
      (response/response)
      (response/content-type "text/html")))

(comment
  ;; Test the handler
  (handler {})

  ;; View raw HTML
  (println (:body (handler {}))))
