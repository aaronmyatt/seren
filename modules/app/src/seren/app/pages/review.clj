(ns seren.app.pages.review
  "Review session page — free recall with optional scaffolding.

   The user always starts with free recall: 'What do you remember about
   this content?' They type (Phase 3) or speak (Phase 4) their response.
   If a scaffold is available (based on their SM-2 state), a 'Show hint'
   button reveals it — but only after they've attempted recall first.

   After submitting, the quality score is applied via SM-2 and the next
   review is scheduled.

   See plan.md § 'Review Flow: Free Recall + Scaffolding'
   See: https://shoelace.style/components/card"
  (:require [seren.app.pages.layout :as layout]
            [ring.util.response :as response]))

(defn handler
  "Ring handler for GET /review/:id. Renders a review session page.
   The review data and content are loaded by the CLJS island via
   /api/reviews/:id/session — the server just renders the shell."
  [request]
  (let [_review-id (get-in request [:path-params :id])]
    (-> (layout/page
          {:title "Review — Seren"
           :nav :review
           :modules [:shared :review]
           :body
           [:main.page-review

            [:header
             [:h1 "Free Recall"]
             [:p {:data-island "review-title"} "Loading..."]]

            ;; Scaffold hint area — hidden by default, revealed on button click
            [:section.scaffold-area {:data-island "scaffold-area"
                                     :style "display:none"}
             [:sl-card
              [:div.scaffold-content {:data-island "scaffold-content"}]]]

            ;; Free recall input area
            [:section.recall-area
             [:sl-card
              [:div {:data-island "recall-form"}
               [:sl-textarea {:id "recall-input"
                              :placeholder "Type everything you remember about this content..."
                              :rows "8"
                              :resize "auto"}]

               [:div.recall-actions
                ;; Hint button — only visible when scaffold is available
                [:sl-button {:id "hint-btn"
                             :variant "text"
                             :size "small"
                             :style "display:none"}
                 "Show hint"]

                [:sl-button {:id "submit-btn"
                             :variant "primary"
                             :size "large"}
                 "Submit recall"]]]]]

            ;; Quality self-assessment — shown after submit (until Phase 4 automates it)
            [:section.quality-area {:data-island "quality-area"
                                    :style "display:none"}
             [:sl-card
              [:h3 "How did that feel?"]
              [:p "Rate your recall (Phase 4 will automate this with similarity scoring)"]
              [:div.quality-buttons {:data-island "quality-buttons"}
               [:sl-button {:data-quality "0" :variant "danger"  :outline true} "0 — Blank"]
               [:sl-button {:data-quality "1" :variant "warning" :outline true} "1 — Barely"]
               [:sl-button {:data-quality "2" :variant "warning" :outline true} "2 — Familiar"]
               [:sl-button {:data-quality "3" :variant "primary" :outline true} "3 — Difficult"]
               [:sl-button {:data-quality "4" :variant "success" :outline true} "4 — Good"]
               [:sl-button {:data-quality "5" :variant "success" :outline true} "5 — Perfect"]]]]

            ;; Result — shown after quality is selected
            [:section.result-area {:data-island "result-area"
                                   :style "display:none"}
             [:sl-card
              [:div {:data-island "result-content"}]]]

            ;; --- Navigation ---
            [:nav.bottom-nav
             [:a.nav-item {:href "/library"} "Library"]
             [:a.nav-item {:href "/dashboard"} "Dashboard"]]]})
        (response/response)
        (response/content-type "text/html"))))

(comment
  (handler {:path-params {:id "test-review-id"}})
  (println (:body (handler {:path-params {:id "test"}}))))
