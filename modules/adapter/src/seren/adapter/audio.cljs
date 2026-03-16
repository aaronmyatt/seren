(ns seren.adapter.audio
  "Browser-side audio recording via MediaRecorder API.

   Captures microphone audio as WebM/Opus chunks, then assembles them into
   a Blob for upload to the server. The server handles transcription via
   whisper.cpp — no browser-side speech recognition needed.

   This replaces the Web Speech API approach (adapter/speech.cljs) which
   required internet connectivity and a trusted certificate. whisper.cpp
   runs fully local, matching Seren's local-network-first design.

   Adapted from: voice-pwa/modules/adapter/src/voice/adapter/audio_recorder.cljs
   See: https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder
   See: https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia"
  (:require [malli.core :as m]))

;; --- State ---

(defonce ^:private recorder-state
  (atom {:media-recorder nil
         :audio-chunks   []
         :active?        false}))

;; --- Feature detection ---

(defn supported?
  "Returns true if MediaRecorder and getUserMedia are available.
   Both require a 'secure context' (HTTPS or localhost).
   See: https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia#security"
  []
  (and (exists? js/navigator.mediaDevices)
       (exists? js/MediaRecorder)))

(m/=> supported? [:=> [:cat] :boolean])

;; --- Lifecycle ---

(defn start!
  "Starts recording audio from the user's microphone.
   Returns a js/Promise that resolves when recording begins.

   Options:
     :on-chunk  (fn [blob]) — called with each audio chunk (~1s intervals)
     :on-error  (fn [error-string]) — called on recording errors

   Audio is captured as WebM/Opus which whisper.cpp can decode directly.
   See: https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/MediaRecorder"
  [{:keys [on-chunk on-error]}]
  (if-not (exists? js/navigator.mediaDevices)
    ;; Secure context check — mediaDevices is undefined over plain HTTP
    (js/Promise.reject
      (js/Error. "Microphone requires HTTPS or localhost."))
    (-> (js/navigator.mediaDevices.getUserMedia #js {:audio true})
        (.then
          (fn [stream]
            (let [recorder (js/MediaRecorder. stream
                            #js {:mimeType "audio/webm;codecs=opus"})]
              (set! (.-ondataavailable recorder)
                    (fn [e]
                      (when (> (.-size (.-data e)) 0)
                        (swap! recorder-state update :audio-chunks conj (.-data e))
                        (when on-chunk (on-chunk (.-data e))))))

              (set! (.-onerror recorder)
                    (fn [e]
                      (when on-error (on-error (str "Recording error: " (.-error e))))))

              ;; Collect data every 1 second for streaming/progress indication
              (.start recorder 1000)
              (swap! recorder-state assoc
                     :media-recorder recorder
                     :audio-chunks []
                     :active? true)
              recorder)))
        (.catch
          (fn [err]
            (when on-error (on-error (str "Microphone access denied: " (.-message err))))
            (throw err))))))

(m/=> start!
      [:=> [:cat [:map
                  [:on-chunk {:optional true} [:=> [:cat :any] :any]]
                  [:on-error {:optional true} [:=> [:cat :string] :any]]]]
       :any])

(defn stop!
  "Stops recording and returns a js/Promise that resolves to an audio Blob.
   Also stops all microphone tracks to release the device indicator.
   See: https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/stop"
  []
  (js/Promise.
    (fn [resolve _reject]
      (let [recorder (:media-recorder @recorder-state)]
        (if-not recorder
          (resolve nil)
          (do
            (set! (.-onstop recorder)
                  (fn [_]
                    (let [chunks (:audio-chunks @recorder-state)
                          blob   (js/Blob. (clj->js chunks)
                                   #js {:type "audio/webm;codecs=opus"})]
                      ;; Release microphone tracks
                      ;; See: https://developer.mozilla.org/en-US/docs/Web/API/MediaStreamTrack/stop
                      (doseq [track (.getTracks (.-stream recorder))]
                        (.stop track))
                      (swap! recorder-state assoc
                             :audio-chunks []
                             :media-recorder nil
                             :active? false)
                      (resolve blob))))
            (.stop recorder)))))))

(m/=> stop! [:=> [:cat] :any])

(defn active?
  "Returns true if audio recording is currently in progress."
  []
  (:active? @recorder-state))

(m/=> active? [:=> [:cat] :boolean])

(defn chunk-count
  "Returns the number of audio chunks recorded so far.
   Useful for UI feedback (e.g. recording duration estimate: chunks × 1s)."
  []
  (count (:audio-chunks @recorder-state)))

(m/=> chunk-count [:=> [:cat] :int])

;; ---- REPL Examples ----
(comment
  ;; Check browser support
  (supported?)

  ;; Start recording with chunk callback
  (-> (start! {:on-chunk (fn [blob] (js/console.log "chunk:" (.-size blob) "bytes"))
               :on-error (fn [err] (js/console.error "error:" err))})
      (.then (fn [_] (js/console.log "Recording started"))))

  ;; Stop and get the audio blob
  (-> (stop!)
      (.then (fn [blob]
               (when blob
                 (js/console.log "Recorded" (.-size blob) "bytes"
                                 "type:" (.-type blob)))))))
