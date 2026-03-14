(ns seren.app.pages.dashboard
  "Dashboard page — upcoming reviews, score history, content stats.

   For Phase 1 this is a simple overview showing total content count
   and a link to the library. Phase 2 will add upcoming review cards.

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
           [:sl-card
            [:div {:data-island "stats"}
             [:p "Loading stats..."]]]

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
