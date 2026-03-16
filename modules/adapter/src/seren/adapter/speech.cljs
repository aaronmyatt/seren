(ns seren.adapter.speech
  "DEPRECATED — Superseded by seren.adapter.audio + seren.adapter.whisper.

   This module used the Web Speech API (SpeechRecognition) for in-browser
   transcription. It was replaced because Web Speech API requires internet
   connectivity and a trusted certificate, which conflicts with Seren's
   local-network-first design. The replacement uses MediaRecorder (audio.cljs)
   to capture audio locally, then whisper.cpp (whisper.clj) for transcription.

   Kept for reference only — not imported by any module.
   See: seren.adapter.audio (replacement)
   See: seren.adapter.whisper (JVM transcription client)"
  (:require [clojure.string :as str]
            [malli.core :as m]))

;; --- State ---

(defonce ^:private recognition-state
  (atom {:recognition nil
         :interim-text ""
         :final-text ""
         :active? false}))

;; --- Feature detection ---

(defn supported?
  "Returns true if the Web Speech API is available in this browser.
   Chrome, Edge, and Safari support it; Firefox does not (as of 2025).
   See: https://caniuse.com/speech-recognition"
  []
  (boolean (or (.-SpeechRecognition js/window)
               (.-webkitSpeechRecognition js/window))))

(m/=> supported? [:=> [:cat] :boolean])

;; --- Lifecycle ---

(defn start!
  "Starts speech recognition with live transcription callbacks.

   Options:
     :on-interim  (fn [text]) — called with partial (not yet final) results
     :on-final    (fn [text]) — called with completed sentence fragments
     :on-error    (fn [error-string]) — called on recognition errors
     :lang        language code (default \"en-US\")

   Returns the SpeechRecognition instance, or nil if unsupported.

   The recognition is set to continuous mode — it keeps listening until
   stop! is called. If the browser auto-stops (after silence), the onend
   handler restarts it automatically.

   See: https://developer.mozilla.org/en-US/docs/Web/API/SpeechRecognition"
  [{:keys [on-interim on-final on-error lang]
    :or   {lang "en-US"}}]
  (let [SpeechRecognition (or (.-SpeechRecognition js/window)
                              (.-webkitSpeechRecognition js/window))]
    (if-not SpeechRecognition
      (do (when on-error (on-error "SpeechRecognition not supported in this browser"))
          nil)
      (let [recognition (SpeechRecognition.)]
        (set! (.-continuous recognition) true)
        (set! (.-interimResults recognition) true)
        (set! (.-lang recognition) lang)
        (set! (.-maxAlternatives recognition) 1)

        ;; Process results — accumulate final text, report interim
        ;; See: https://developer.mozilla.org/en-US/docs/Web/API/SpeechRecognition/result_event
        (set! (.-onresult recognition)
              (fn [e]
                (let [results (.-results e)
                      len     (.-length results)]
                  (loop [i 0 interim "" final ""]
                    (if (< i len)
                      (let [result (aget results i)
                            text   (.-transcript (aget result 0))]
                        (if (.-isFinal result)
                          (recur (inc i) interim (str final text " "))
                          (recur (inc i) (str interim text) final)))
                      (do
                        (swap! recognition-state assoc
                               :interim-text interim
                               :final-text (str (:final-text @recognition-state) final))
                        (when (and on-interim (seq interim))
                          (on-interim interim))
                        (when (and on-final (seq final))
                          (on-final (str/trim final)))))))))

        ;; Handle errors — "network" means no internet or untrusted cert
        ;; See: https://developer.mozilla.org/en-US/docs/Web/API/SpeechRecognition/error_event
        (set! (.-onerror recognition)
              (fn [e]
                (let [error (.-error e)]
                  (when (= "network" error)
                    (swap! recognition-state assoc :recognition nil :active? false))
                  (when on-error (on-error error)))))

        ;; Auto-restart on end — browser stops after silence
        (set! (.-onend recognition)
              (fn [_]
                (when (:active? @recognition-state)
                  (.start recognition))))

        (.start recognition)
        (swap! recognition-state assoc
               :recognition recognition
               :active? true
               :final-text ""
               :interim-text "")
        recognition))))

(m/=> start!
      [:=> [:cat [:map
                  [:on-interim {:optional true} [:=> [:cat :string] :any]]
                  [:on-final {:optional true} [:=> [:cat :string] :any]]
                  [:on-error {:optional true} [:=> [:cat :string] :any]]
                  [:lang {:optional true} :string]]]
       :any])

(defn stop!
  "Stops speech recognition and returns the accumulated final transcript.
   Resets state for the next recording session."
  []
  (let [{:keys [recognition final-text]} @recognition-state]
    (swap! recognition-state assoc :active? false :recognition nil)
    (when recognition
      (.stop recognition))
    (let [text (str/trim (or final-text ""))]
      (swap! recognition-state assoc :final-text "" :interim-text "")
      text)))

(m/=> stop! [:=> [:cat] :string])

(defn get-interim-text
  "Returns the current interim (not yet finalized) speech text.
   Useful for showing a live preview while the user is still speaking."
  []
  (:interim-text @recognition-state))

(m/=> get-interim-text [:=> [:cat] :string])

(defn active?
  "Returns true if speech recognition is currently running."
  []
  (:active? @recognition-state))

(m/=> active? [:=> [:cat] :boolean])

;; ---- REPL Examples ----
(comment
  ;; Check browser support
  (supported?)
  ;; => true (on Chrome/Safari)

  ;; Start recognising
  (start! {:on-interim (fn [text] (js/console.log "interim:" text))
           :on-final   (fn [text] (js/console.log "FINAL:" text))
           :on-error   (fn [err] (js/console.error "speech error:" err))})

  ;; Stop and get accumulated text
  (stop!)
  ;; => "the accumulated final transcript text"

  ;; Check live interim text
  (get-interim-text))
