(ns seren.app.islands.dashboard
  "Dashboard page island — shows content stats and upcoming reviews.

   Fetches content from /api/content and reviews from /api/reviews/due,
   then renders a stats summary and a list of due review cards.
   Each review card shows the content title, review shape, and how
   overdue it is, with a button to start the review (Phase 3).

   See: https://jasonformat.com/islands-architecture/"
  (:require [cljs.reader :as reader]
            [seren.app.islands.shared :as shared]))

(defn- fetch-edn
  "Fetches EDN from a URL. Returns a promise of parsed EDN."
  [url]
  (-> (js/fetch url #js {:headers #js {"Accept" "application/edn"}})
      (.then (fn [resp] (.text resp)))
      (.then (fn [text] (reader/read-string text)))))

;; --- Safe DOM construction helpers ---
;; Avoid innerHTML to prevent XSS — build elements programmatically.
;; See: https://developer.mozilla.org/en-US/docs/Web/API/Document/createElement

(defn- create-el
  "Creates a DOM element, optionally setting text content and CSS class."
  ([tag] (js/document.createElement tag))
  ([tag text] (doto (js/document.createElement tag)
                (set! -textContent text)))
  ([tag text class-name] (doto (js/document.createElement tag)
                           (set! -textContent text)
                           (.. -classList (add class-name)))))

(defn- append-children!
  "Appends multiple child elements to a parent."
  [parent & children]
  (doseq [child children]
    (.appendChild parent child))
  parent)

;; --- Stats rendering ---

(defn- render-stats!
  "Fetches content + review counts and renders the stats grid."
  []
  (when-let [el (shared/find-island "stats")]
    (-> (js/Promise.all
          #js [(fetch-edn "/api/content")
               (fetch-edn "/api/reviews/due")])
        (.then (fn [results]
                 (let [content-resp (aget results 0)
                       reviews-resp (aget results 1)
                       contents     (:content content-resp)
                       due-reviews  (:reviews reviews-resp)
                       total        (count contents)
                       total-chunks (reduce + 0 (map #(count (:chunks %)) contents))
                       due-count    (count due-reviews)
                       grid         (create-el "div")]
                   (.. grid -classList (add "stats-grid"))
                   (doseq [[value label] [[total "Content items"]
                                          [total-chunks "Total chunks"]
                                          [due-count "Reviews due"]]]
                     (let [stat (create-el "div")]
                       (.. stat -classList (add "stat"))
                       (append-children! stat
                                         (create-el "span" (str value) "stat-value")
                                         (create-el "span" label "stat-label"))
                       (.appendChild grid stat)))
                   (set! (.-textContent el) "")
                   (.appendChild el grid))))
        (.catch (fn [err]
                  (js/console.error "[dashboard] Failed to load stats:" err)
                  (set! (.-textContent el) "Could not load stats."))))))

;; --- Due reviews rendering ---

(defn- format-due-at
  "Formats a due-at timestamp relative to now.
   Returns a human-readable string like 'Due now', 'Overdue by 2d', etc."
  [due-at-ms]
  (let [now    (.getTime (js/Date.))
        diff   (- now due-at-ms)
        ;; Convert ms to hours for readability
        hours  (/ diff 3600000)]
    (cond
      (< hours 0)  (let [h (Math/abs hours)]
                     (cond
                       (< h 1)  "Due in < 1h"
                       (< h 24) (str "Due in " (Math/round h) "h")
                       :else    (str "Due in " (Math/round (/ h 24)) "d")))
      (< hours 1)  "Due now"
      (< hours 24) (str "Overdue " (Math/round hours) "h")
      :else        (str "Overdue " (Math/round (/ hours 24)) "d"))))

(defn- scaffold-label
  "Returns a human-readable label for a scaffold level keyword.
   See plan.md § 'Review Flow: Free Recall + Scaffolding'"
  [scaffold]
  (case scaffold
    :none           "Free recall"
    :headings       "Headings hint"
    :summary        "Summary hint"
    :keyword-blanks "Fill-in-blanks hint"
    (str scaffold)))

(defn- render-review-card
  "Creates a DOM element for a single due review card.
   Shows the content title, scaffold level, due status, and a Review Now button.
   Phase 4a: the button navigates directly to /review/:id."
  [review content-map]
  (let [content-id (:content-id review)
        content    (get content-map content-id)
        title      (or (:title content) "Unknown content")
        card       (create-el "div")]
    (.. card -classList (add "review-card"))
    (let [review-btn (doto (create-el "sl-button" "Review Now")
                       (.setAttribute "variant" "primary")
                       (.setAttribute "size" "small"))]
      (.addEventListener review-btn "click"
        (fn [_e] (set! js/window.location (str "/review/" (:id review)))))
      (append-children!
        card
        (create-el "h4" title)
        (let [meta (create-el "div")]
          (.. meta -classList (add "review-meta"))
          (append-children!
            meta
            (create-el "span" (scaffold-label (:scaffold review)) "review-shape")
            (create-el "span" (format-due-at (:due-at review)) "review-due")))
        review-btn))
    card))

(defn- render-due-reviews!
  "Fetches due reviews + content, renders review cards into the island."
  []
  (when-let [el (shared/find-island "due-reviews")]
    (-> (js/Promise.all
          #js [(fetch-edn "/api/reviews/due")
               (fetch-edn "/api/content")])
        (.then (fn [results]
                 (let [reviews-resp (aget results 0)
                       content-resp (aget results 1)
                       due-reviews  (:reviews reviews-resp)
                       contents     (:content content-resp)
                       ;; Build id→content map for title lookups
                       content-map  (reduce (fn [m c] (assoc m (:id c) c))
                                            {} contents)]
                   (set! (.-textContent el) "")
                   (if (empty? due-reviews)
                     (.appendChild el
                       (create-el "p" "No reviews due. Add content to get started!" "empty-state"))
                     (let [list-el (create-el "div")]
                       (.. list-el -classList (add "review-list"))
                       (doseq [review due-reviews]
                         (.appendChild list-el (render-review-card review content-map)))
                       (.appendChild el list-el))))))
        (.catch (fn [err]
                  (js/console.error "[dashboard] Failed to load reviews:" err)
                  (set! (.-textContent el) "Could not load reviews."))))))

;; --- Initialization ---

(defn ^:export init
  "Mounts the dashboard island — stats + due reviews."
  []
  (render-stats!)
  (render-due-reviews!)
  (js/console.log "[seren] dashboard island mounted"))

;; Initialize on load
(init)
