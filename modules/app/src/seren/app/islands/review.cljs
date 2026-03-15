(ns seren.app.islands.review
  "Review session island — drives the free-recall flow with scaffold hints.

   Flow:
   1. Load review + content data from /api/reviews/:id/session
   2. Show the free-recall prompt (title as only cue by default)
   3. User types what they remember
   4. Optionally reveals scaffold hint (if available)
   5. User submits → quality self-assessment buttons appear
   6. Quality selected → POST to /api/reviews/:id/complete
   7. Show result (next review date, encouragement)

   Phase 4 will add: voice input via Web Speech API, automatic quality
   derivation from similarity scoring (replacing the self-assessment buttons).

   Functions are ordered bottom-up (callees before callers) so ClojureScript's
   single-pass compiler resolves all symbols without forward declarations.

   See plan.md § 'Review Flow: Free Recall + Scaffolding'
   See: https://jasonformat.com/islands-architecture/"
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [seren.app.islands.shared :as shared]))

;; --- State ---

(defonce ^:private state
  (atom {:review-id nil
         :review    nil
         :content   nil
         :scaffold  nil}))

;; --- EDN helpers ---

(defn- fetch-edn
  "Fetches EDN from a URL. Returns a promise of parsed EDN."
  [url]
  (-> (js/fetch url #js {:headers #js {"Accept" "application/edn"}})
      (.then (fn [resp] (.text resp)))
      (.then (fn [text] (reader/read-string text)))))

(defn- post-edn
  "POSTs EDN to a URL. Returns a promise of parsed EDN response."
  [url body]
  (-> (js/fetch url #js {:method  "POST"
                         :headers #js {"Content-Type" "application/edn"
                                       "Accept"       "application/edn"}
                         :body    (pr-str body)})
      (.then (fn [resp] (.text resp)))
      (.then (fn [text] (reader/read-string text)))))

;; --- DOM helpers ---

(defn- create-el
  "Creates a DOM element, optionally setting text and CSS class."
  ([tag] (js/document.createElement tag))
  ([tag text] (doto (js/document.createElement tag)
                (set! -textContent text)))
  ([tag text class-name] (doto (js/document.createElement tag)
                           (set! -textContent text)
                           (.. -classList (add class-name)))))

(defn- show! [el] (set! (.. el -style -display) ""))
(defn- hide! [el] (set! (.. el -style -display) "none"))

;; --- Extract review ID from URL ---

(defn- review-id-from-url
  "Extracts the review ID from the current URL path.
   Expects /review/:id"
  []
  (let [path  (.-pathname js/location)
        parts (str/split path #"/")]
    ;; ["" "review" "some-id"]
    (when (>= (count parts) 3)
      (nth parts 2))))

;; --- Leaf renderers (no forward references) ---

(defn- render-error!
  "Renders an error message in the title area."
  [message]
  (when-let [el (shared/find-island "review-title")]
    (set! (.-textContent el) message)))

(defn- render-result!
  "Shows the review result — next review date and encouragement."
  [response quality]
  ;; Hide quality area
  (when-let [area (shared/find-island "quality-area")]
    (hide! area))

  ;; Show result area
  (when-let [area (shared/find-island "result-area")]
    (show! area)
    (when-let [content-el (shared/find-island "result-content")]
      (set! (.-textContent content-el) "")
      (let [next-review (:next-review response)
            interval    (:interval next-review)
            ;; Encouragement based on quality
            message     (cond
                          (>= quality 4) "Excellent recall!"
                          (= quality 3)  "Good effort — you passed!"
                          (= quality 2)  "Getting there — keep practicing."
                          :else          "Don't worry, spaced repetition works. You'll see this again soon.")
            wrapper     (create-el "div")]
        (.. wrapper -classList (add "result-summary"))
        (.appendChild wrapper (create-el "h3" message))
        (.appendChild wrapper
          (create-el "p"
            (if (zero? interval)
              "Next review: tomorrow"
              (str "Next review in " interval " day" (when (> interval 1) "s")))))
        (let [link (create-el "a" "Back to Dashboard")]
          (set! (.-href link) "/dashboard")
          (.. link -classList (add "result-link"))
          (.appendChild wrapper link))
        (.appendChild content-el wrapper)))))

;; --- Server interaction ---

(defn- complete-review!
  "Submits the quality score to the server and shows the result."
  [quality]
  ;; Disable all quality buttons
  (when-let [container (shared/find-island "quality-buttons")]
    (doseq [btn (array-seq (.querySelectorAll container "[data-quality]"))]
      (set! (.-disabled btn) true)))

  (let [rid (:review-id @state)]
    (-> (post-edn (str "/api/reviews/" rid "/complete") {:quality quality})
        (.then (fn [response]
                 (if (:success response)
                   (render-result! response quality)
                   (render-error! (or (:reason response) "Failed to complete review")))))
        (.catch (fn [err]
                  (js/console.error "[review] Failed to complete review:" err)
                  (render-error! "Could not submit review."))))))

;; --- Event handlers ---
;; Defined after the functions they call (complete-review!, render-error!, etc.)

(defn- setup-quality-buttons!
  "Attaches click handlers to the quality rating buttons."
  []
  (when-let [container (shared/find-island "quality-buttons")]
    (doseq [btn (array-seq (.querySelectorAll container "[data-quality]"))]
      (.addEventListener btn "click"
        (fn [_e]
          (let [quality (js/parseInt (.getAttribute btn "data-quality") 10)]
            (complete-review! quality)))))))

(defn- on-hint-click!
  "Reveals the scaffold hint area."
  [_event]
  (let [{:keys [scaffold]} @state]
    (when-let [area (shared/find-island "scaffold-area")]
      (show! area)
      (when-let [content-el (shared/find-island "scaffold-content")]
        ;; Render scaffold text, preserving newlines
        (set! (.-textContent content-el) "")
        (doseq [line (str/split-lines scaffold)]
          (let [p (create-el "p" line)]
            (.appendChild content-el p))))
      ;; Hide the hint button after revealing
      (when-let [btn (js/document.getElementById "hint-btn")]
        (hide! btn)))))

(defn- on-submit-click!
  "Handles the submit button — shows the quality self-assessment."
  [_event]
  (let [input-el (js/document.getElementById "recall-input")
        text     (when input-el (.-value input-el))]
    (when (and text (not (str/blank? text)))
      ;; Store the recall text for Phase 4 similarity scoring
      (swap! state assoc :recall-text text)

      ;; Disable the form
      (when input-el (set! (.-disabled input-el) true))
      (when-let [btn (js/document.getElementById "submit-btn")]
        (set! (.-disabled btn) true)
        (set! (.-textContent btn) "Submitted"))

      ;; Show quality assessment
      (when-let [area (shared/find-island "quality-area")]
        (show! area)
        (setup-quality-buttons!)))))

;; --- Session rendering ---
;; Defined after event handlers it references (on-hint-click!, on-submit-click!)

(defn- render-session!
  "Renders the review session UI after data is loaded."
  []
  (let [{:keys [content scaffold]} @state]
    ;; Set the title
    (when-let [el (shared/find-island "review-title")]
      (set! (.-textContent el) (:title content)))

    ;; Set up scaffold hint if available
    (when (and scaffold (not= "" scaffold))
      (when-let [btn (js/document.getElementById "hint-btn")]
        (show! btn)
        (.addEventListener btn "click" on-hint-click!)))

    ;; Set up submit button
    (when-let [btn (js/document.getElementById "submit-btn")]
      (.addEventListener btn "click" on-submit-click!))))

;; --- Session loading ---
;; Defined after render-session! and render-error! which it calls

(defn- load-session!
  "Fetches review and content data, then initialises the UI."
  []
  (let [rid (review-id-from-url)]
    (when rid
      (swap! state assoc :review-id rid)
      (-> (fetch-edn (str "/api/reviews/" rid "/session"))
          (.then (fn [response]
                   (if (:success response)
                     (do
                       (swap! state merge
                              {:review   (:review response)
                               :content  (:content response)
                               :scaffold (:scaffold response)})
                       (render-session!))
                     (render-error! (or (:reason response) "Review not found")))))
          (.catch (fn [err]
                    (js/console.error "[review] Failed to load session:" err)
                    (render-error! "Could not load review session.")))))))

;; --- Initialization ---

(defn ^:export init
  "Mounts the review session island."
  []
  (load-session!)
  (js/console.log "[seren] review island mounted"))

;; Initialize on load
(init)
