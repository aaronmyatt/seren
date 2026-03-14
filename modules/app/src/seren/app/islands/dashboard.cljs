(ns seren.app.islands.dashboard
  "Dashboard page island — shows content stats and upcoming reviews.

   Fetches content list from /api/content and renders a summary.
   Phase 2 will add upcoming review cards with due dates.

   See: https://jasonformat.com/islands-architecture/"
  (:require [cljs.reader :as reader]
            [seren.app.islands.shared :as shared]))

(defn- fetch-edn
  "Fetches EDN from a URL. Returns a promise of parsed EDN."
  [url]
  (-> (js/fetch url #js {:headers #js {"Accept" "application/edn"}})
      (.then (fn [resp] (.text resp)))
      (.then (fn [text] (reader/read-string text)))))

(defn- render-stats!
  "Fetches content count and renders stats into the stats island."
  []
  (when-let [el (shared/find-island "stats")]
    (-> (fetch-edn "/api/content")
        (.then (fn [response]
                 (let [contents (:content response)
                       total (count contents)
                       total-chunks (reduce + 0 (map #(count (:chunks %)) contents))]
                   (set! (.-innerHTML el)
                         (str "<div class='stats-grid'>"
                              "<div class='stat'>"
                              "<span class='stat-value'>" total "</span>"
                              "<span class='stat-label'>Content items</span>"
                              "</div>"
                              "<div class='stat'>"
                              "<span class='stat-value'>" total-chunks "</span>"
                              "<span class='stat-label'>Total chunks</span>"
                              "</div>"
                              "<div class='stat'>"
                              "<span class='stat-value'>0</span>"
                              "<span class='stat-label'>Reviews due</span>"
                              "</div>"
                              "</div>")))))
        (.catch (fn [err]
                  (js/console.error "[dashboard] Failed to load stats:" err)
                  (set! (.-innerHTML el) "<p>Could not load stats.</p>"))))))

(defn ^:export init
  "Mounts the dashboard island."
  []
  (render-stats!)
  (js/console.log "[seren] dashboard island mounted"))

;; Initialize on load
(init)
