(ns seren.app.islands.review
  "Review session island — drives the free-recall flow with voice input
   (via whisper.cpp), automatic similarity scoring, and scaffold hints.

   Flow:
   1. Load review + content data from /api/reviews/:id/session
   2. Show the free-recall prompt (title as only cue by default)
   3. User speaks (recorded locally) or types what they remember
   4. Optionally reveals scaffold hint (if available)
   5. User submits → audio sent to /api/transcribe (whisper.cpp)
      → transcript sent to /api/reviews/:id/score (similarity scoring)
   6. Show result: similarity %, missed chunks, next review date

   Voice recording uses MediaRecorder (adapter/audio.cljs). The audio blob
   is POSTed to the JVM backend which proxies to whisper.cpp for local
   transcription — no internet required.

   Functions are ordered bottom-up (callees before callers) so ClojureScript's
   single-pass compiler resolves all symbols without forward declarations.

   See plan.md § 'Voice Free-Recall Flow'
   See: https://jasonformat.com/islands-architecture/"
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [seren.adapter.audio :as audio]
            [seren.app.islands.shared :as shared]))

;; --- State ---

(defonce ^:private state
  (atom {:review-id   nil
         :review      nil
         :content     nil
         :scaffold    nil
         :recording?  false
         ;; Holds the audio Blob after recording stops, ready for transcription
         :audio-blob  nil}))

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

(defn- post-audio
  "POSTs raw audio bytes to a URL. Returns a promise of parsed EDN response.
   The server expects the raw audio blob, not multipart."
  [url blob]
  (-> (js/fetch url #js {:method  "POST"
                         :headers #js {"Content-Type" (.-type blob)
                                       "Accept"       "application/edn"}
                         :body    blob})
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
    (when (>= (count parts) 3)
      (nth parts 2))))

;; --- Leaf renderers (no forward references) ---

(defn- render-error!
  "Renders an error message in the title area."
  [message]
  (when-let [el (shared/find-island "review-title")]
    (set! (.-textContent el) message)))

(defn- render-missed-chunks!
  "Renders the list of missed content chunks in the result area.
   See plan.md § 'Voice Free-Recall Flow'"
  [parent-el missed-chunks]
  (when (seq missed-chunks)
    (let [section (create-el "div")]
      (.. section -classList (add "missed-chunks"))
      (.appendChild section (create-el "h4" "What you missed:"))
      (doseq [chunk missed-chunks]
        (.appendChild section (create-el "p" (:text chunk) "missed-chunk")))
      (.appendChild parent-el section))))

(defn- render-result!
  "Shows the review result — similarity %, missed chunks, next review date."
  [response]
  (let [{:keys [similarity quality missed-chunks next-review]} response]
    ;; Hide quality area
    (when-let [area (shared/find-island "quality-area")]
      (hide! area))

    ;; Show result area
    (when-let [area (shared/find-island "result-area")]
      (show! area)
      (when-let [content-el (shared/find-island "result-content")]
        (set! (.-textContent content-el) "")
        (let [interval (:interval next-review)
              pct      (when similarity (js/Math.round (* 100 similarity)))
              message  (cond
                         (>= quality 4) "Excellent recall!"
                         (= quality 3)  "Good effort — you passed!"
                         (= quality 2)  "Getting there — keep practicing."
                         :else          "Don't worry, spaced repetition works. You'll see this again soon.")
              wrapper  (create-el "div")]
          (.. wrapper -classList (add "result-summary"))

          ;; Similarity score display (only for auto-scored, not manual)
          (when pct
            (let [score-el (create-el "div")]
              (.. score-el -classList (add "similarity-score"))
              (.appendChild score-el (create-el "span" (str pct "%") "score-value"))
              (.appendChild score-el (create-el "span" "recall similarity" "score-label"))
              (.appendChild wrapper score-el)))

          (.appendChild wrapper (create-el "h3" message))

          ;; Missed chunks
          (render-missed-chunks! wrapper missed-chunks)

          ;; Next review info
          (.appendChild wrapper
            (create-el "p"
              (if (zero? interval)
                "Next review: tomorrow"
                (str "Next review in " interval " day" (when (> interval 1) "s")))
              "next-review-info"))

          (let [link (create-el "a" "Back to Dashboard")]
            (set! (.-href link) "/dashboard")
            (.. link -classList (add "result-link"))
            (.appendChild wrapper link))
          (.appendChild content-el wrapper))))))

;; --- Transcription + scoring pipeline ---

(defn- set-status!
  "Updates the status text element."
  [text]
  (when-let [el (js/document.getElementById "recall-status")]
    (set! (.-textContent el) text)
    (show! el)))

(defn- submit-transcript-for-scoring!
  "Sends transcript text to the scoring endpoint."
  [transcript]
  (let [rid (:review-id @state)]
    (set-status! "Scoring your recall...")
    (-> (post-edn (str "/api/reviews/" rid "/score")
                  {:transcript transcript})
        (.then (fn [response]
                 (if (:success response)
                   (render-result! response)
                   (render-error! (or (:reason response) "Scoring failed")))))
        (.catch (fn [err]
                  (js/console.error "[review] Scoring failed:" err)
                  (render-error! "Could not score recall."))))))

(defn- transcribe-and-score!
  "Sends audio blob to whisper.cpp for transcription, then scores the result."
  [audio-blob]
  (set-status! "Transcribing with whisper.cpp...")
  (-> (post-audio "/api/transcribe" audio-blob)
      (.then (fn [response]
               (if (:success response)
                 (let [text (:text response)]
                   ;; Show the transcript in the textarea
                   (when-let [el (js/document.getElementById "recall-input")]
                     (set! (.-value el) text))
                   (set-status! (str "Transcript: \"" (subs text 0 (min 80 (count text)))
                                     (when (> (count text) 80) "...") "\""))
                   ;; Now score it
                   (submit-transcript-for-scoring! text))
                 (do
                   (set-status! "")
                   (render-error! (str "Transcription failed: "
                                       (or (:reason response) "Unknown error")))))))
      (.catch (fn [err]
                (js/console.error "[review] Transcription failed:" err)
                (set-status! "")
                (render-error! "Could not transcribe audio.")))))

;; --- Fallback: manual quality ---

(defn- complete-review-manual!
  "Fallback: submits quality score directly (Phase 3 behaviour)."
  [quality]
  (when-let [container (shared/find-island "quality-buttons")]
    (doseq [btn (array-seq (.querySelectorAll container "[data-quality]"))]
      (set! (.-disabled btn) true)))

  (let [rid (:review-id @state)]
    (-> (post-edn (str "/api/reviews/" rid "/complete") {:quality quality})
        (.then (fn [response]
                 (if (:success response)
                   (render-result! (merge response
                                          {:similarity nil
                                           :quality    quality
                                           :missed-chunks []}))
                   (render-error! (or (:reason response) "Failed to complete review")))))
        (.catch (fn [err]
                  (js/console.error "[review] Failed to complete review:" err)
                  (render-error! "Could not submit review."))))))

;; --- Event handlers ---

(defn- setup-quality-buttons!
  "Attaches click handlers to the quality rating buttons (manual fallback)."
  []
  (when-let [container (shared/find-island "quality-buttons")]
    (doseq [btn (array-seq (.querySelectorAll container "[data-quality]"))]
      (.addEventListener btn "click"
        (fn [_e]
          (let [quality (js/parseInt (.getAttribute btn "data-quality") 10)]
            (complete-review-manual! quality)))))))

(defn- on-hint-click!
  "Reveals the scaffold hint area."
  [_event]
  (let [{:keys [scaffold]} @state]
    (when-let [area (shared/find-island "scaffold-area")]
      (show! area)
      (when-let [content-el (shared/find-island "scaffold-content")]
        (set! (.-textContent content-el) "")
        (doseq [line (str/split-lines scaffold)]
          (.appendChild content-el (create-el "p" line))))
      (when-let [btn (js/document.getElementById "hint-btn")]
        (hide! btn)))))

(defn- disable-submit-ui!
  "Disables submit button and textarea, shows processing state."
  [button-text]
  (when-let [btn (js/document.getElementById "submit-btn")]
    (set! (.-disabled btn) true)
    (set! (.-textContent btn) button-text))
  (when-let [input-el (js/document.getElementById "recall-input")]
    (set! (.-disabled input-el) true)))

(defn- on-submit-click!
  "Handles the submit button. Three paths:
   1. Currently recording → stop, transcribe via whisper.cpp, score
   2. Audio blob stashed (recorded then stopped) → transcribe + score
   3. Typed text in textarea → score directly

   See plan.md § 'Voice Free-Recall Flow'"
  [_event]
  (cond
    ;; Path 1: still recording — stop and transcribe
    (:recording? @state)
    (do
      (swap! state assoc :recording? false)
      (when-let [voice-btn (js/document.getElementById "voice-btn")]
        (set! (.-textContent voice-btn) "Voice")
        (.. voice-btn -classList (remove "recording")))
      (disable-submit-ui! "Processing...")
      (-> (audio/stop!)
          (.then (fn [blob]
                   (swap! state assoc :audio-blob nil)
                   (if blob
                     (transcribe-and-score! blob)
                     (render-error! "No audio recorded."))))
          (.catch (fn [err]
                    (js/console.error "[review] Stop recording failed:" err)
                    (render-error! "Recording failed.")))))

    ;; Path 2: recording already stopped, blob waiting
    (:audio-blob @state)
    (let [blob (:audio-blob @state)]
      (swap! state assoc :audio-blob nil)
      (disable-submit-ui! "Processing...")
      (transcribe-and-score! blob))

    ;; Path 3: typed text — score directly
    :else
    (let [input-el (js/document.getElementById "recall-input")
          text     (when input-el (.-value input-el))]
      (if (and text (not (str/blank? text)))
        (do
          (disable-submit-ui! "Scoring...")
          (submit-transcript-for-scoring! text))
        (set-status! "Type or record something first.")))))

(defn- update-recording-timer!
  "Updates the recording duration display based on chunk count."
  []
  (when (:recording? @state)
    (let [seconds (audio/chunk-count)]
      (set-status! (str "Recording... " seconds "s"))
      (js/setTimeout update-recording-timer! 1000))))

(defn- on-voice-toggle!
  "Toggles audio recording on/off.
   When stopping, captures the audio blob and stashes it in state so the
   Submit button can transcribe it. This avoids the bug where stopping via
   the Voice button discards the audio before Submit is pressed."
  [_event]
  (let [voice-btn (js/document.getElementById "voice-btn")]
    (if (:recording? @state)
      ;; Stop recording — capture blob and stash for Submit
      (do
        (swap! state assoc :recording? false)
        (when voice-btn
          (set! (.-textContent voice-btn) "Voice")
          (.. voice-btn -classList (remove "recording")))
        (-> (audio/stop!)
            (.then (fn [blob]
                     (swap! state assoc :audio-blob blob)
                     (set-status! "Recording stopped. Press Submit to transcribe and score.")))
            (.catch (fn [err]
                      (js/console.error "[review] Stop failed:" err)
                      (set-status! "Recording error. Try again.")))))
      ;; Start recording
      (do
        ;; Clear any previously stashed blob
        (swap! state assoc :audio-blob nil)
        (-> (audio/start!
              {:on-error (fn [err]
                           (js/console.warn "[review] Recording error:" err)
                           (set-status! (str "Mic error: " err))
                           (swap! state assoc :recording? false)
                           (when voice-btn
                             (set! (.-textContent voice-btn) "Voice")
                             (.. voice-btn -classList (remove "recording"))))})
            (.then (fn [_]
                     (swap! state assoc :recording? true)
                     (when voice-btn
                       (set! (.-textContent voice-btn) "Stop")
                       (.. voice-btn -classList (add "recording")))
                     (update-recording-timer!)))
            (.catch (fn [err]
                      (js/console.error "[review] Mic access failed:" err)
                      (set-status! "Could not access microphone."))))))))

;; --- Session rendering ---

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

    ;; Set up voice button if MediaRecorder is available
    (when-let [voice-btn (js/document.getElementById "voice-btn")]
      (if (audio/supported?)
        (do (show! voice-btn)
            (.addEventListener voice-btn "click" on-voice-toggle!))
        (hide! voice-btn)))

    ;; Set up submit button
    (when-let [btn (js/document.getElementById "submit-btn")]
      (.addEventListener btn "click" on-submit-click!))

    ;; Set up manual quality fallback toggle
    (when-let [btn (js/document.getElementById "manual-quality-btn")]
      (.addEventListener btn "click"
        (fn [_e]
          (when-let [area (shared/find-island "quality-area")]
            (show! area)
            (setup-quality-buttons!)))))))

;; --- Session loading ---

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
