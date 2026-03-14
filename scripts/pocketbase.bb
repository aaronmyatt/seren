#!/usr/bin/env bb

;; Downloads the latest PocketBase binary for the current platform.
;; Usage: bb pb:install
;;
;; Detects OS and architecture, fetches the latest release from GitHub,
;; and extracts the binary to .pocketbase/pocketbase.
;;
;; PocketBase is a single-binary backend with SQLite, auth, and realtime.
;; See: https://pocketbase.io/docs/

(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[babashka.process :refer [shell]]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def install-dir ".pocketbase")

(defn detect-os
  "Detects the operating system for PocketBase download naming.
  Returns one of: \"darwin\", \"linux\", \"windows\"."
  []
  (let [os-name (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os-name "mac")   "darwin"
      (str/includes? os-name "linux") "linux"
      (str/includes? os-name "win")   "windows"
      :else (do (println (str "Error: Unsupported OS '" os-name "'"))
                (System/exit 1)))))

(defn detect-arch
  "Detects the CPU architecture for PocketBase download naming.
  Returns one of: \"amd64\", \"arm64\"."
  []
  (let [arch (System/getProperty "os.arch")]
    (case arch
      ("amd64" "x86_64")  "amd64"
      ("aarch64" "arm64") "arm64"
      (do (println (str "Error: Unsupported architecture '" arch "'"))
          (System/exit 1)))))

(defn fetch-latest-version
  "Fetches the latest PocketBase release version from GitHub API.
  Returns version string without 'v' prefix (e.g. \"0.36.6\").
  See: https://docs.github.com/en/rest/releases/releases#get-the-latest-release"
  []
  (println "Checking latest PocketBase version...")
  (let [resp (http/get "https://api.github.com/repos/pocketbase/pocketbase/releases/latest"
                       {:headers {"Accept" "application/vnd.github.v3+json"
                                  "User-Agent" "clojure-fcis-template"}})
        body (json/parse-string (:body resp) true)
        tag  (:tag_name body)]
    (when-not tag
      (println "Error: Could not determine latest version from GitHub API")
      (System/exit 1))
    ;; Strip leading 'v' if present
    (str/replace tag #"^v" "")))

(defn download-url
  "Constructs the download URL for a PocketBase release.
  See: https://github.com/pocketbase/pocketbase/releases"
  [version os arch]
  (str "https://github.com/pocketbase/pocketbase/releases/download/"
       "v" version "/pocketbase_" version "_" os "_" arch ".zip"))

(defn download-and-extract!
  "Downloads the PocketBase zip and extracts the binary to install-dir."
  [url]
  (let [zip-path (str install-dir "/pocketbase.zip")]
    (fs/create-dirs install-dir)

    (println (str "Downloading: " url))
    (let [resp (http/get url {:as :stream})]
      (with-open [in (:body resp)]
        (io/copy in (io/file zip-path))))

    (println (str "Extracting to " install-dir "/"))
    (shell {:dir install-dir} "unzip" "-o" "-q" "pocketbase.zip")

    ;; Clean up zip
    (fs/delete zip-path)

    ;; Make binary executable (no-op on Windows)
    (let [binary (str install-dir "/pocketbase")]
      (when (fs/exists? binary)
        (shell "chmod" "+x" binary)))))

(defn check-existing-version
  "Checks if PocketBase is already installed and returns its version, or nil."
  []
  (let [binary (str install-dir "/pocketbase")]
    (when (fs/exists? binary)
      (try
        (let [result (shell {:out :string :err :string}
                            binary "--version")]
          (some-> (:out result)
                  str/trim
                  ;; Output format: "pocketbase version 0.36.6"
                  (str/replace #"^pocketbase version " "")))
        (catch Exception _ nil)))))

;; --- Main ---

(let [existing (check-existing-version)
      latest   (fetch-latest-version)
      os       (detect-os)
      arch     (detect-arch)]

  (println (str "Platform: " os "/" arch))

  (if (and existing (= existing latest))
    (println (str "PocketBase v" latest " is already installed. Up to date."))
    (do
      (when existing
        (println (str "Upgrading from v" existing " to v" latest "...")))
      (download-and-extract! (download-url latest os arch))
      (println (str "PocketBase v" latest " installed to " install-dir "/pocketbase")))))
