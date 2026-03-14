(ns seren.app.pages.library
  "Library page — ingest new content and browse existing items.

   Server renders the page shell with an input form and a content feed.
   The CLJS island in seren.app.islands.library handles form submission
   (POST /api/ingest) and dynamically refreshes the content list.

   See: https://shoelace.style/components/textarea
   See: https://shoelace.style/components/card"
  (:require [seren.app.pages.layout :as layout]
            [ring.util.response :as response]))

(defn handler
  "Ring handler for GET /library. Renders the content library page."
  [_request]
  (-> (layout/page
        {:title "Library — Seren"
         :nav :library
         :modules [:shared :library]
         :body
         [:main.page-library

          [:header
           [:h1 "Library"]
           [:p "Add content to your learning queue"]]

          ;; --- Ingestion form ---
          [:section.ingest-form
           [:sl-card
            [:form {:data-island "ingest-form"}

             [:sl-textarea {:name "text"
                            :label "Paste content"
                            :placeholder "Paste an article, blog post, documentation, or notes here..."
                            :rows "6"
                            :resize "auto"}]

             [:sl-input {:name "title"
                         :label "Title (optional)"
                         :placeholder "Will be auto-derived from headings if left blank"
                         :clearable true}]

             [:sl-input {:name "url"
                         :label "Source URL (optional)"
                         :placeholder "https://..."
                         :type "url"
                         :clearable true}]

             [:div.form-actions
              [:sl-button {:type "submit"
                           :variant "primary"}
               "Add to Library"]]]]]

          ;; --- Content feed ---
          [:section.content-feed
           [:h2 "Your Content"]
           [:div {:data-island "content-list"}
            [:p.empty-state "Loading..."]]]

          ;; --- Navigation ---
          [:nav.bottom-nav
           [:a.nav-item.active {:href "/library"} "Library"]
           [:a.nav-item {:href "/dashboard"} "Dashboard"]]]})
      (response/response)
      (response/content-type "text/html")))

(comment
  (handler {})
  (println (:body (handler {}))))
