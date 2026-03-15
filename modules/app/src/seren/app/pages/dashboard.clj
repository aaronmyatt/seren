(ns seren.app.pages.dashboard
  "Dashboard page — upcoming reviews, score history, content stats.

   Phase 2 adds the 'due for review' section showing content items that
   need practice. Reviews are fetched by the dashboard island from
   /api/reviews/due and enriched with content titles from /api/content.

   See: https://shoelace.style/components/card"
  (:require [seren.app.pages.layout :as layout]
            [ring.util.response :as response]))

(defn handler
  "Ring handler for GET /dashboard. Renders the Seren dashboard."
  [_request]
  (-> (layout/page
        {:title "Dashboard — Seren"
         :nav :dashboard
         :modules [:shared :dashboard]
         :body
         [:main.page-dashboard

          [:header
           [:h1 "Dashboard"]
           [:p "Your learning overview"]]

          [:section.dashboard-content
           ;; Stats overview
           [:sl-card
            [:div {:data-island "stats"}
             [:p "Loading stats..."]]]

           ;; Due reviews — Phase 2
           [:sl-card
            [:h3 "Due for Review"]
            [:div {:data-island "due-reviews"}
             [:p "Loading reviews..."]]]

           ;; Call to action
           [:sl-card
            [:h3 "Ready to learn?"]
            [:p "Add content to your library, then review it with voice-driven free recall."]
            [:sl-button {:href "/library" :variant "primary"} "Go to Library"]]]

          ;; --- Navigation ---
          [:nav.bottom-nav
           [:a.nav-item {:href "/library"} "Library"]
           [:a.nav-item.active {:href "/dashboard"} "Dashboard"]]]})
      (response/response)
      (response/content-type "text/html")))

(comment
  (handler {})
  (println (:body (handler {}))))
