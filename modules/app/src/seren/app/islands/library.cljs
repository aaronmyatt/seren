(ns seren.app.islands.library
  "Library page island — handles content ingestion form and content feed.

   Intercepts the form submit, POSTs EDN to /api/ingest, then refreshes
   the content list. All API communication uses EDN (natural for CLJS).

   See: https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API
   See: https://cljs.github.io/api/cljs.reader/read-string"
  (:require [cljs.reader :as reader]
            [seren.app.islands.shared :as shared]))

;; --- API helpers ---

(defn- fetch-edn
  "Sends a fetch request with EDN body. Returns a promise of parsed EDN response.
   See: https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch"
  ([url] (fetch-edn url nil))
  ([url opts]
   (-> (js/fetch url (clj->js (merge {:headers {"Content-Type" "application/edn"}} opts)))
       (.then (fn [resp] (.text resp)))
       (.then (fn [text] (reader/read-string text))))))

(defn- post-edn
  "POSTs EDN data to a URL. Returns a promise of parsed EDN response."
  [url data]
  (fetch-edn url {:method "POST"
                   :body (pr-str data)}))

;; --- Content rendering ---

(defn- format-date
  "Formats a unix timestamp to a readable date string.
   See: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date"
  [timestamp]
  (let [d (js/Date. timestamp)]
    (.toLocaleDateString d "en-GB" #js {:day "numeric" :month "short" :year "numeric"})))

(defn- create-el
  "Creates a DOM element with optional text content and CSS class.
   Builds DOM nodes safely — no innerHTML, no XSS risk.
   See: https://developer.mozilla.org/en-US/docs/Web/API/Document/createElement"
  ([tag] (js/document.createElement tag))
  ([tag text] (doto (js/document.createElement tag)
                (set! -textContent text)))
  ([tag text class-name] (doto (js/document.createElement tag)
                           (set! -textContent text)
                           (.. -classList (add class-name)))))

(defn- render-content-card
  "Creates a DOM element for a single content card.
   Uses DOM construction rather than innerHTML for safety."
  [content]
  (let [card    (create-el "sl-card")
        wrapper (doto (create-el "div")
                  (.. -classList (add "content-card")))
        title   (create-el "h3" (:title content))
        summary (create-el "p" (:summary content) "content-summary")
        meta    (doto (create-el "div")
                  (.. -classList (add "content-meta")))
        chunks  (doto (create-el "sl-badge" (str (count (:chunks content)) " chunks"))
                  (.setAttribute "variant" "neutral"))
        date    (create-el "span" (format-date (:created-at content)) "content-date")]
    ;; Assemble the card
    (.appendChild wrapper title)
    (.appendChild wrapper summary)
    (.appendChild meta chunks)
    (doseq [tag (take 4 (:tags content))]
      (.appendChild meta (doto (create-el "sl-badge" tag)
                           (.setAttribute "variant" "primary"))))
    (.appendChild meta date)
    (.appendChild wrapper meta)
    (.appendChild card wrapper)
    card))

(defn- render-content-list!
  "Fetches all content from the API and renders cards into the content-list island."
  []
  (when-let [container (shared/find-island "content-list")]
    (-> (fetch-edn "/api/content")
        (.then (fn [response]
                 (let [contents (:content response)]
                   (set! (.-innerHTML container) "")
                   (if (empty? contents)
                     (set! (.-innerHTML container)
                           "<p class='empty-state'>No content yet. Paste something above to get started!</p>")
                     (doseq [c contents]
                       (.appendChild container (render-content-card c)))))))
        (.catch (fn [err]
                  (js/console.error "[library] Failed to load content:" err)
                  (set! (.-innerHTML container)
                        "<p class='error-state'>Failed to load content. Is the server running?</p>"))))))

;; --- Form handling ---

(defn- get-form-value
  "Gets the value of a Shoelace form element by name.
   Shoelace components store their value in the .value property.
   See: https://shoelace.style/getting-started/form-controls"
  [form field-name]
  (when-let [el (.querySelector form (str "[name='" field-name "']"))]
    (let [v (.-value el)]
      (when-not (empty? v) v))))

(defn- clear-form!
  "Clears all form fields by resetting Shoelace component values."
  [form]
  (doseq [el (array-seq (.querySelectorAll form "sl-textarea, sl-input"))]
    (set! (.-value el) "")))

(defn- attach-ingest-form!
  "Attaches submit handler to the ingest form island.
   On submit: reads form values, POSTs to /api/ingest, refreshes list."
  []
  (when-let [form (shared/find-island "ingest-form")]
    (.addEventListener form "submit"
      (fn [e]
        (.preventDefault e)
        (let [text  (get-form-value form "text")
              title (get-form-value form "title")
              url   (get-form-value form "url")
              submit-btn (.querySelector form "sl-button[type='submit']")]
          ;; Validate — at least text is required for now
          (when text
            ;; Show loading state
            (when submit-btn
              (set! (.-loading submit-btn) true))
            (-> (post-edn "/api/ingest" {:text text :title title :url url})
                (.then (fn [result]
                         (when submit-btn
                           (set! (.-loading submit-btn) false))
                         (if (:success result)
                           (do
                             (clear-form! form)
                             (render-content-list!))
                           (js/alert (str "Ingestion failed: " (:reason result))))))
                (.catch (fn [err]
                          (when submit-btn
                            (set! (.-loading submit-btn) false))
                          (js/console.error "[library] Ingest failed:" err)
                          (js/alert "Failed to submit. Is the server running?"))))))))))

;; --- Lifecycle ---

(defn ^:export init
  "Mounts the library island. Attaches form handler and loads content list."
  []
  (attach-ingest-form!)
  (render-content-list!)
  (js/console.log "[seren] library island mounted"))

;; Initialize on load
(init)
