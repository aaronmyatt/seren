(ns seren.adapter.whisper
  "JVM adapter for whisper.cpp speech-to-text.

   whisper.cpp runs as a local HTTP server alongside the JVM backend.
   This adapter sends audio data to it and returns the transcript text.

   The browser records audio as WebM/Opus via MediaRecorder. Since whisper.cpp
   only accepts WAV (PCM 16-bit), we convert with ffmpeg before sending.

   The server exposes an OpenAI-compatible API at /inference:
     POST /inference  (multipart/form-data with 'file' field)
     Returns: {\"text\": \"transcribed text...\"}

   See: https://github.com/ggml-org/whisper.cpp/tree/master/examples/server
   See: https://ffmpeg.org/ffmpeg.html"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m])
  (:import [java.net HttpURLConnection URL]
           [java.io ByteArrayOutputStream File]))

;; --- Configuration ---

(def ^:private default-url
  "Default whisper.cpp server URL. Matches the port in bb.edn whisper:start."
  "http://127.0.0.1:8178")

;; --- Audio conversion ---
;; whisper.cpp only reads WAV (PCM 16-bit, 16 kHz mono).
;; The browser's MediaRecorder produces WebM/Opus, so we pipe through ffmpeg.
;; See: https://ffmpeg.org/ffmpeg.html#Audio-Options

(defn- webm->wav
  "Converts audio bytes from any ffmpeg-supported format to WAV PCM 16-bit 16 kHz mono.
   Writes input to a temp file, runs ffmpeg, reads back the WAV bytes.
   Returns WAV byte array, or throws on failure."
  [audio-bytes]
  (let [in-file  (File/createTempFile "seren-audio-" ".webm")
        out-file (File/createTempFile "seren-audio-" ".wav")]
    (try
      ;; Write input bytes to temp file
      (with-open [out (io/output-stream in-file)]
        (.write out ^bytes audio-bytes))
      ;; Convert: ffmpeg -y -i input.webm -ar 16000 -ac 1 -f wav output.wav
      ;; -y: overwrite output, -ar: sample rate, -ac: mono, -f: force WAV format
      ;; See: https://trac.ffmpeg.org/wiki/AudioChannelManipulation
      (let [proc (-> (ProcessBuilder.
                       ["ffmpeg" "-y"
                        "-i" (.getAbsolutePath in-file)
                        "-ar" "16000"
                        "-ac" "1"
                        "-sample_fmt" "s16"
                        "-f" "wav"
                        (.getAbsolutePath out-file)])
                     (.redirectErrorStream true)
                     (.start))
            exit (.waitFor proc)]
        (when-not (zero? exit)
          (let [stderr (slurp (.getInputStream proc))]
            (throw (ex-info (str "ffmpeg conversion failed (exit " exit ")")
                            {:exit exit :stderr stderr}))))
        ;; Read WAV bytes
        (with-open [in (io/input-stream out-file)
                    baos (ByteArrayOutputStream.)]
          (io/copy in baos)
          (.toByteArray baos)))
      (finally
        (.delete in-file)
        (.delete out-file)))))

;; --- HTTP multipart helper ---
;; whisper.cpp server expects multipart/form-data with an audio file.
;; We build the multipart request manually to avoid pulling in clj-http.
;; See: https://www.rfc-editor.org/rfc/rfc2046#section-5.1

(defn- multipart-body
  "Builds a multipart/form-data body for an audio file upload.
   Returns {:boundary string :body byte-array}."
  [audio-bytes filename content-type]
  (let [boundary (str "----SernWhisper" (System/currentTimeMillis))
        baos     (ByteArrayOutputStream.)
        write    (fn [^String s] (.write baos (.getBytes s "UTF-8")))]
    ;; File part
    (write (str "--" boundary "\r\n"))
    (write (str "Content-Disposition: form-data; name=\"file\"; filename=\"" filename "\"\r\n"))
    (write (str "Content-Type: " content-type "\r\n\r\n"))
    (.write baos ^bytes audio-bytes)
    (write "\r\n")
    ;; Response format part
    (write (str "--" boundary "\r\n"))
    (write "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n")
    (write "json\r\n")
    ;; Language part
    (write (str "--" boundary "\r\n"))
    (write "Content-Disposition: form-data; name=\"language\"\r\n\r\n")
    (write "en\r\n")
    ;; Close
    (write (str "--" boundary "--\r\n"))
    {:boundary boundary
     :body     (.toByteArray baos)}))

;; --- Public API ---

(defn transcribe
  "Sends audio bytes to the whisper.cpp server and returns the transcript.

   Audio is automatically converted to WAV (PCM 16-bit, 16 kHz mono) via
   ffmpeg if the content-type is not audio/wav. whisper.cpp only accepts WAV.

   Parameters:
     audio-bytes  — raw audio data (WAV, WebM, or any ffmpeg-supported format)
     opts         — optional map:
       :url           whisper server URL (default http://127.0.0.1:8178)
       :filename      name hint for the server (default \"audio.wav\")
       :content-type  MIME type of the input (default \"audio/wav\")

   Returns {:success true :text \"transcribed text\"}
   or {:success false :reason \"error message\"}.

   Requires: ffmpeg on PATH (brew install ffmpeg)
   See: https://github.com/ggml-org/whisper.cpp/tree/master/examples/server"
  ([audio-bytes] (transcribe audio-bytes {}))
  ([audio-bytes {:keys [url content-type]
                 :or   {url          default-url
                        content-type "audio/wav"}}]
   (try
     ;; Convert to WAV if input is not already WAV
     ;; See: https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/mimeType
     (let [needs-convert? (not (str/starts-with? content-type "audio/wav"))
           wav-bytes      (if needs-convert?
                            (do (println (str "[whisper] Converting " content-type " → WAV via ffmpeg"))
                                (webm->wav audio-bytes))
                            audio-bytes)
           {:keys [boundary body]} (multipart-body wav-bytes "audio.wav" "audio/wav")
           conn (doto ^HttpURLConnection
                      (.openConnection (URL. (str url "/inference")))
                  (.setRequestMethod "POST")
                  (.setDoOutput true)
                  (.setRequestProperty "Content-Type"
                                       (str "multipart/form-data; boundary=" boundary))
                  (.setConnectTimeout 5000)
                  (.setReadTimeout 30000))]
       (with-open [out (.getOutputStream conn)]
         (.write out ^bytes body))
       (let [status (.getResponseCode conn)]
         (if (= 200 status)
           ;; whisper.cpp returns {"text": "..."} — extract with regex to avoid
           ;; pulling in a JSON library for this single use case.
           ;; See: https://github.com/ggml-org/whisper.cpp/tree/master/examples/server
           (let [response-text (slurp (.getInputStream conn))
                 text          (or (second (re-find #"\"text\"\s*:\s*\"([^\"]*)\""
                                                    response-text))
                                   "")]
             {:success true
              :text    (str/trim text)})
           {:success false
            :reason  (str "Whisper server returned HTTP " status ": "
                          (try (slurp (.getErrorStream conn))
                               (catch Exception _ "")))})))
     (catch java.net.ConnectException _
       {:success false
        :reason  "Whisper server not reachable. Start it with: bb whisper:start"})
     (catch Exception e
       {:success false
        :reason  (str "Transcription failed: " (.getMessage e))}))))

(m/=> transcribe
      [:=> [:cat :any]
       [:or
        [:map [:success :boolean] [:text :string]]
        [:map [:success :boolean] [:reason :string]]]])

(defn available?
  "Checks if the whisper.cpp server is running and reachable."
  ([] (available? default-url))
  ([url]
   (try
     (let [conn (doto ^HttpURLConnection
                      (.openConnection (URL. (str url "/")))
                  (.setConnectTimeout 2000)
                  (.setReadTimeout 2000))]
       (< (.getResponseCode conn) 500))
     (catch Exception _ false))))

(m/=> available? [:=> [:cat] :boolean])

(comment
  ;; Check if whisper server is running
  (available?)

  ;; Transcribe a WAV file (no conversion needed)
  (let [audio-bytes (-> (io/file "test.wav") io/input-stream .readAllBytes)]
    (transcribe audio-bytes))

  ;; Transcribe a WebM file (auto-converts via ffmpeg)
  (let [audio-bytes (-> (io/file "recording.webm") io/input-stream .readAllBytes)]
    (transcribe audio-bytes {:content-type "audio/webm;codecs=opus"})))
