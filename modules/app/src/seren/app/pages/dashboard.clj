(ns seren.app.pages.dashboard
  "Dashboard page — the landing page for Seren.
   Shows upcoming reviews, recent content, and score history.

   This is the starting point for the PWA. Further pages (library,
   review session) will be added in later phases.

   See: https://shoelace.style/components/card"
  (:require [seren.app.pages.layout :as layout]
            [ring.util.response :as response]))

(defn handler
  "Ring handler for GET /dashboard. Renders the Seren dashboard."
  [_request]
  (-> (layout/page
        {:title "Seren — Learning Agent"
         :modules [:shared :dashboard]
         :body
         [:main.page-dashboard
          [:header
           [:h1 "Seren"]
           [:p "Your learning companion"]]

          [:section.dashboard-content
           [:sl-card
            [:p "No content ingested yet."]
            [:p "Paste a URL or some text to get started."]]]]})
      (response/response)
      (response/content-type "text/html")))

(comment
  (handler {})
  (println (:body (handler {}))))
