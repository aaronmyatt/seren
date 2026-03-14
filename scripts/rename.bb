#!/usr/bin/env bb

;; Renames the `seren` namespace prefix throughout the project template.
;; Usage: bb rename <new-name>
;;
;; The new name must be a valid Clojure namespace segment: lowercase letters,
;; digits, and hyphens (e.g., "myproject", "cool-app").
;;
;; This script:
;;   1. Renames directories (modules/*/src/seren/ → modules/*/src/<new>/)
;;   2. Replaces `seren` in all source, config, and doc files
;;   3. Cleans build artifacts that cache old namespace references
;;
;; See: https://book.babashka.org/#_file_system_utilities

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def old-ns "seren")

(def dir-roots
  "Directory trees containing `seren/` segments that need renaming."
  ["modules/core/src"
   "modules/core/test"
   "modules/adapter/src"
   "modules/adapter/test"
   "modules/app/src"
   "modules/app/test"
   "shared/src"])

(def file-globs
  "Glob patterns for files whose contents need `seren` replaced."
  ["**/*.clj"
   "**/*.cljs"
   "**/*.cljc"
   "**/*.bb"
   "bb.edn"
   "deps.edn"
   "modules/*/deps.edn"
   "shadow-cljs.edn"
   "package.json"
   "CLAUDE.md"
   ".claude/settings.local.json"])

(defn validate-name
  "Validates the new namespace name. Returns nil if valid, error string if not."
  [new-name]
  (cond
    (str/blank? new-name)
    "Name cannot be blank"

    (= new-name old-ns)
    (str "Name is already '" old-ns "' — nothing to rename")

    (not (re-matches #"^[a-z][a-z0-9-]*$" new-name))
    (str "'" new-name "' is not a valid Clojure namespace segment.\n"
         "Must start with a lowercase letter and contain only lowercase letters, digits, and hyphens.")))

(defn rename-dirs!
  "Renames `seren/` directory segments to `new-name/` under each root.
  Returns count of directories renamed."
  [new-name]
  (let [counter (atom 0)]
    (doseq [root dir-roots]
      (let [old-dir (fs/path root old-ns)
            new-dir (fs/path root new-name)]
        (when (fs/exists? old-dir)
          (fs/move old-dir new-dir)
          (swap! counter inc)
          (println (str "  dir: " old-dir " → " new-dir)))))
    @counter))

(defn replace-in-files!
  "Replaces all occurrences of `seren` with `new-name` in matching files.
  Returns count of files modified."
  [new-name]
  (let [counter (atom 0)
        all-files (mapcat #(fs/glob "." %) file-globs)]
    (doseq [f all-files
            :let [path (str f)
                  content (slurp path)
                  updated (str/replace content old-ns new-name)]
            :when (not= content updated)]
      (spit path updated)
      (swap! counter inc)
      (println (str "  file: " path)))
    @counter))

(defn clean-caches!
  "Removes build caches that may reference old namespaces."
  []
  (doseq [dir [".clj-kondo/.cache" ".shadow-cljs" "target" ".cpcache" "out"
                "modules/core/.cpcache" "modules/adapter/.cpcache" "modules/app/.cpcache"]]
    (when (fs/exists? dir)
      (fs/delete-tree dir)
      (println (str "  cleaned: " dir)))))

;; --- Main ---

(let [args *command-line-args*]
  (when (or (empty? args) (> (count args) 1))
    (println "Usage: bb rename <new-name>")
    (println "Example: bb rename myproject")
    (System/exit 1))

  (let [new-name (first args)]
    (when-let [err (validate-name new-name)]
      (println (str "Error: " err))
      (System/exit 1))

    (println (str "Renaming '" old-ns "' → '" new-name "' ...\n"))

    (println "Renaming directories:")
    (let [dir-count (rename-dirs! new-name)]

      (println "\nReplacing in files:")
      (let [file-count (replace-in-files! new-name)]

        (println "\nCleaning build caches:")
        (clean-caches!)

        (println (str "\nDone! Renamed " dir-count " directories and updated " file-count " files."))
        (println "Run `bb test:all` and `bb test:cljs` to verify everything works.")))))
