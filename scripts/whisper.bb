#!/usr/bin/env bb

;; Installs whisper.cpp server binary + a base model for local speech-to-text.
;; Usage: bb whisper:install
;;
;; Strategy:
;;   1. macOS: `brew install whisper-cpp` then symlink the server binary
;;   2. Linux: download pre-built binary from GitHub releases
;;   3. Fallback: prompt user to build from source
;;
;; whisper.cpp is a C/C++ port of OpenAI's Whisper model.
;; The "server" binary exposes an HTTP API at /inference.
;; See: https://github.com/ggml-org/whisper.cpp
;; See: https://github.com/ggml-org/whisper.cpp/tree/master/examples/server

(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[babashka.process :refer [shell process]]
         '[clojure.string :as str])

(def install-dir ".whisper")
(def model-dir (str install-dir "/models"))
(def server-path (str install-dir "/whisper-server"))

;; Use the "base.en" model as default — good balance of speed and accuracy.
;; ~142 MB download. Prefer medium.en (~1.5 GB) for better accuracy if available.
;; See: https://github.com/ggml-org/whisper.cpp/tree/master/models
(def default-model "ggml-base.en.bin")
(def model-url (str "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" default-model))

;; --- Server installation ---

(defn brew-available?
  "Checks if Homebrew is installed.
   See: https://brew.sh"
  []
  (some? (fs/which "brew")))

(defn install-via-brew!
  "Installs whisper.cpp via Homebrew and symlinks the server binary.
   Homebrew builds from source with Metal/Accelerate support on Apple Silicon.
   See: https://formulae.brew.sh/formula/whisper-cpp"
  []
  (println "Installing whisper-cpp via Homebrew...")
  (shell "brew" "install" "whisper-cpp")
  ;; Find the installed whisper-server binary
  ;; Homebrew installs to $(brew --prefix)/bin/whisper-server
  (let [brew-prefix (str/trim (:out @(process ["brew" "--prefix" "whisper-cpp"]
                                               {:out :string})))
        ;; The binary may be named whisper-server or whisper-cpp-server
        ;; depending on the formula version
        bin-dir     (str brew-prefix "/bin")
        candidates  ["whisper-server" "whisper-cpp-server" "server"]
        binary      (or (first (filter #(fs/exists? (str bin-dir "/" %)) candidates))
                        ;; Broader search: any executable in the Homebrew prefix
                        (first (map fs/file-name
                                    (filter #(str/includes? (str %) "server")
                                            (fs/glob bin-dir "*server*")))))]
    (if binary
      (let [source (str bin-dir "/" binary)]
        (fs/create-dirs install-dir)
        ;; Symlink so bb.edn always finds it at .whisper/whisper-server
        (when (fs/exists? server-path)
          (fs/delete server-path))
        (fs/create-sym-link server-path (fs/absolutize source))
        (println (str "Linked " server-path " → " source)))
      (do
        (println "Warning: whisper-cpp installed but server binary not found in Homebrew prefix.")
        (println (str "Searched: " bin-dir))
        (println "Try: brew info whisper-cpp   to see installed files.")))))

(defn install-via-github!
  "Downloads a pre-built binary from GitHub releases (Linux).
   See: https://github.com/ggml-org/whisper.cpp/releases"
  []
  (println "Attempting to download pre-built binary from GitHub...")
  (let [arch (let [a (System/getProperty "os.arch")]
               (case a
                 ("amd64" "x86_64") "x86_64"
                 ("aarch64" "arm64") "aarch64"
                 a))
        ;; Try the ggml-org repo (current) then ggerganov (legacy)
        repos ["ggml-org/whisper.cpp" "ggerganov/whisper.cpp"]]
    (loop [[repo & more] repos]
      (if-not repo
        (do
          (println "No pre-built binary found for linux/" arch)
          (println "Build from source: https://github.com/ggml-org/whisper.cpp#build")
          (println "  git clone https://github.com/ggml-org/whisper.cpp && cd whisper.cpp")
          (println "  cmake -B build && cmake --build build --config Release -t whisper-server")
          (println (str "  cp build/bin/whisper-server " (fs/absolutize server-path))))
        (let [_ (println (str "Checking " repo "..."))
              resp (try (http/get (str "https://api.github.com/repos/" repo "/releases/latest")
                                  {:headers {"Accept" "application/vnd.github.v3+json"
                                             "User-Agent" "clojure-seren"}
                                   :throw false})
                        (catch Exception _ nil))
              ok?  (and resp (= 200 (:status resp)))]
          (if-not ok?
            (do (println (str "  Could not reach " repo " releases API"))
                (recur more))
            (let [body   (cheshire.core/parse-string (:body resp) true)
                  assets (:assets body)
                  ;; Match patterns like whisper-server-*-linux-x86_64.zip
                  match  (first
                           (filter #(and (str/includes? (:name %) "server")
                                         (str/includes? (:name %) "linux")
                                         (or (str/includes? (:name %) arch)
                                             (and (= arch "x86_64")
                                                  (str/includes? (:name %) "x64"))))
                                   assets))]
              (if match
                (let [url      (:browser_download_url match)
                      filename (:name match)
                      zip-path (str install-dir "/" filename)]
                  (fs/create-dirs install-dir)
                  (println (str "Downloading: " filename))
                  (let [dl (http/get url {:as :stream
                                          :headers {"Accept" "application/octet-stream"
                                                    "User-Agent" "clojure-seren"}})]
                    (with-open [in (:body dl)]
                      (clojure.java.io/copy in (clojure.java.io/file zip-path))))
                  (println (str "Extracting to " install-dir "/"))
                  (shell {:dir install-dir} "unzip" "-o" "-q" (fs/file-name zip-path))
                  (fs/delete zip-path)
                  ;; Find and rename the binary
                  (let [binary (or (first (fs/glob install-dir "whisper-server"))
                                   (first (fs/glob install-dir "**/whisper-server"))
                                   (first (fs/glob install-dir "*server*")))]
                    (when binary
                      (when (not= (str binary) server-path)
                        (fs/move binary server-path {:replace-existing true}))
                      (shell "chmod" "+x" server-path)
                      (println (str "Installed to " server-path)))))
                (do (println (str "  No matching asset in " repo " for linux/" arch))
                    (recur more))))))))))

(defn install-server!
  "Installs the whisper-server binary using the best available method."
  []
  (if (fs/exists? server-path)
    (println (str "whisper-server already installed: " server-path))
    (let [os (let [n (str/lower-case (System/getProperty "os.name"))]
               (cond (str/includes? n "mac") "darwin"
                     (str/includes? n "linux") "linux"
                     :else "unknown"))]
      (cond
        ;; macOS: prefer Homebrew — it builds with Metal/Accelerate support
        (and (= os "darwin") (brew-available?))
        (install-via-brew!)

        ;; Linux: try GitHub releases
        (= os "linux")
        (install-via-github!)

        ;; macOS without Homebrew
        (= os "darwin")
        (do
          (println "Homebrew not found. Install it first:")
          (println "  /bin/bash -c \"$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"")
          (println "Then run: bb whisper:install"))

        :else
        (println (str "Unsupported OS: " os))))))

;; --- Model download ---

(defn download-model!
  "Downloads the base.en Whisper model if no model is already present.
   Skips download if any ggml model exists (e.g. medium.en via symlink).
   See: https://huggingface.co/ggerganov/whisper.cpp"
  []
  (let [model-path      (str model-dir "/" default-model)
        ;; Check for any existing model — covers symlinked medium.en from voice-pwa
        existing-models (seq (fs/glob model-dir "ggml-*.bin"))]
    (cond
      existing-models
      (do (println "Model(s) already available:")
          (doseq [m existing-models]
            (println (str "  " (fs/file-name m)))))

      :else
      (do
        (fs/create-dirs model-dir)
        (println (str "Downloading model: " default-model " (~142 MB)..."))
        (let [resp (http/get model-url {:as :stream
                                         :headers {"User-Agent" "clojure-seren"}})]
          (with-open [in (:body resp)]
            (clojure.java.io/copy in (clojure.java.io/file model-path))))
        (println (str "Model saved to " model-path))))))

;; --- Main ---

(install-server!)
(download-model!)

(println "\nDone! Start with: bb whisper:start")
