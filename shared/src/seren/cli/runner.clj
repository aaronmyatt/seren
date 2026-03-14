(ns seren.cli.runner
  "Generic CLI runner that introspects Malli-annotated functions
   and exposes them as CLI commands.

   Usage:
     clojure -M:cli/core --namespaces seren.core.user -- list
     clojure -M:cli/core --namespaces seren.core.user -- describe seren.core.user/validate-email
     clojure -M:cli/core --namespaces seren.core.user -- run seren.core.user/validate-email '\"test@example.com\"'"
  (:require [malli.core :as m]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn collect-functions!
  "Loads the given namespaces and collects all Malli-annotated functions.
   Returns a sorted map of {qualified-symbol {:schema ... :doc ... :arglists ...}}."
  [namespace-syms]
  (doseq [ns-sym namespace-syms]
    (require ns-sym))
  (into (sorted-map)
        (for [[ns-sym fns] (m/function-schemas)
              :when (contains? (set namespace-syms) ns-sym)
              [fn-name {:keys [schema]}] fns
              :let [qualified (symbol (str ns-sym) (str fn-name))
                    v (find-var qualified)
                    mt (meta v)]]
          [qualified
           {:schema   schema
            :doc      (:doc mt "")
            :arglists (:arglists mt)
            :var      v}])))

(defn- format-schema
  "Pretty-prints a Malli schema to a string."
  [schema]
  (with-out-str (pp/pprint (m/form schema))))

(defn list-functions
  "Lists all discovered functions with brief descriptions."
  [fn-registry]
  (if (empty? fn-registry)
    (println "  No Malli-annotated functions found in the specified namespaces.")
    (do
      (println)
      (println "  Available functions:")
      (println "  --------------------")
      (doseq [[sym {:keys [doc]}] fn-registry]
        (println (format "  %-45s %s" sym (first (str/split-lines (or doc ""))))))
      (println)
      (println (str "  " (count fn-registry) " function(s) found."))
      (println "  Use 'describe <fn>' for details or 'run <fn> <args...>' to invoke."))))

(defn describe-function
  "Shows full details for a function: schema, docstring, arglists."
  [fn-registry fn-sym-str]
  (let [fn-sym (symbol fn-sym-str)]
    (if-let [{:keys [schema doc arglists]} (get fn-registry fn-sym)]
      (do
        (println)
        (println "  Function:" fn-sym)
        (println "  Docstring:" (or doc "(none)"))
        (println "  Arglists:" (pr-str arglists))
        (println)
        (println "  Schema:")
        (println "  " (str/trim (format-schema schema)))
        (println)
        ;; Show example CLI invocation
        (when-let [params (second (m/form schema))] ; [:=> [:cat ...] ...]
          (let [param-schemas (rest params)] ; skip :cat
            (println "  Example CLI invocation:")
            (println (str "    run " fn-sym " "
                          (str/join " " (map (fn [_] "<edn-value>") param-schemas)))))))
      (do
        (println "  Function not found:" fn-sym-str)
        (println "  Available functions:")
        (doseq [sym (keys fn-registry)]
          (println "   " sym))))))

(defn run-function
  "Invokes a function with EDN-parsed arguments and pretty-prints the result."
  [fn-registry fn-sym-str args]
  (let [fn-sym (symbol fn-sym-str)]
    (if-let [{:keys [var]} (get fn-registry fn-sym)]
      (let [parsed-args (mapv edn/read-string args)
            result (apply @var parsed-args)]
        (pp/pprint result))
      (do
        (println "  Function not found:" fn-sym-str)
        (println "  Use 'list' to see available functions.")))))

(defn- parse-args
  "Parses CLI arguments into {:namespaces [...] :command ... :args [...]}."
  [args]
  (let [[pre-separator post-separator] (split-with #(not= "--" %) args)
        cli-args (rest post-separator) ; drop the "--"
        ;; Parse --namespaces flag from pre-separator
        ns-pairs (partition 2 pre-separator)
        namespaces (some (fn [[flag val]]
                           (when (= flag "--namespaces")
                             (mapv symbol (str/split val #","))))
                         ns-pairs)]
    {:namespaces (or namespaces [])
     :command    (first cli-args)
     :args       (rest cli-args)}))

(defn -main
  "CLI entry point.

   Commands:
     list                        - list all annotated functions
     describe <qualified-fn>     - show function details and schema
     run <qualified-fn> <args>   - invoke with EDN arguments"
  [& raw-args]
  (let [{:keys [namespaces command args]} (parse-args raw-args)]
    (when (empty? namespaces)
      (println "Error: No namespaces specified. Use --namespaces ns1,ns2")
      (System/exit 1))
    (let [registry (collect-functions! namespaces)]
      (case command
        "list"     (list-functions registry)
        "describe" (describe-function registry (first args))
        "run"      (run-function registry (first args) (rest args))
        (nil "help") (do
                       (println)
                       (println "  FCIS CLI Runner")
                       (println "  ================")
                       (println "  Commands:")
                       (println "    list                         List all annotated functions")
                       (println "    describe <qualified-fn>      Show function details and schema")
                       (println "    run <qualified-fn> <args>    Invoke a function with EDN arguments")
                       (println)
                       (list-functions registry))
        (do
          (println "  Unknown command:" command)
          (println "  Use 'list', 'describe <fn>', or 'run <fn> <args>'."))))))
