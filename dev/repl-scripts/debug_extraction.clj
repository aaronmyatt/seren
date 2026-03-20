;; Debug script — evaluate each form in your REPL to verify the extraction pipeline.
;; Start with: bb nrepl
;;
;; This script tests three layers independently:
;;   1. Core: chunk-by-heading (pure, no IO)
;;   2. Adapter: extract-text (Jsoup HTML → markdown text)
;;   3. End-to-end: fetch-and-extract → process-content → chunks

(require '[clojure.string :as str])

;; ============================================================
;; STEP 1: Test chunk-by-heading in isolation (pure function)
;; ============================================================
;; This should produce 3 chunks: preamble + 2 sections.

(require '[seren.core.content :as content])

(def sample-text
  "Preamble paragraph.\n\n## Why Clojure?\n\nBecause it's great.\n\nAnother paragraph.\n\n## Immutability\n\nData doesn't change.")

(println "=== chunk-by-heading ===")
(let [chunks (content/chunk-by-heading sample-text)]
  (println "Count:" (count chunks))
  (doseq [c chunks]
    (println (str "  [" (:index c) "] " (pr-str (:text c))))))
;; Expected output:
;;   Count: 3
;;   [0] "Preamble paragraph."
;;   [1] "## Why Clojure?\nBecause it's great.\nAnother paragraph."
;;   [2] "## Immutability\nData doesn't change."

;; ============================================================
;; STEP 2: Test chunk-text (paragraph-level, for plain text)
;; ============================================================
;; Without headings, this should produce paragraph-level chunks.

(println "\n=== chunk-text (no headings) ===")
(let [plain "First paragraph.\n\nSecond paragraph.\n\nThird paragraph."
      chunks (content/chunk-text plain)]
  (println "Count:" (count chunks))
  (doseq [c chunks]
    (println (str "  [" (:index c) "] " (pr-str (:text c))))))
;; Expected: 3 chunks, one per paragraph

;; ============================================================
;; STEP 3: Test process-content routing (headings → chunk-by-heading)
;; ============================================================

(println "\n=== process-content with headings ===")
(let [result (content/process-content {:text sample-text :title "Test"})
      chunks (:chunks result)]
  (println "Chunks:" (count chunks))
  (println "Headings:" (:headings result))
  (doseq [c chunks]
    (println (str "  [" (:index c) "] " (subs (:text c) 0 (min 60 (count (:text c))))))))
;; Expected: 3 chunks (heading-aware), 2 headings

(println "\n=== process-content without headings ===")
(let [result (content/process-content {:text "First.\n\nSecond.\n\nThird." :title "Plain"})
      chunks (:chunks result)]
  (println "Chunks:" (count chunks))
  (doseq [c chunks]
    (println (str "  [" (:index c) "] " (pr-str (:text c))))))
;; Expected: 3 chunks (paragraph-level)

;; ============================================================
;; STEP 4: Test extract-text against real HTML (Jsoup)
;; ============================================================

(import '[org.jsoup Jsoup]
        '[org.jsoup.nodes Document Element])

;; 4a: Test with synthetic HTML that mimics clojure.org structure
(println "\n=== extract-text: synthetic Asciidoctor HTML ===")
(let [html "<html><body>
  <div class=\"clj-content-container\">
    <h1>Rationale</h1>
    <div id=\"toc\" class=\"toc\"><ul><li>nav item</li></ul></div>
    <div class=\"paragraph\"><p>Preamble paragraph one.</p></div>
    <div class=\"paragraph\"><p>Preamble paragraph two.</p></div>
    <div class=\"sect1\">
      <h2>Why Clojure?</h2>
      <div class=\"sectionbody\">
        <div class=\"paragraph\"><p>Because it's great.</p></div>
        <div class=\"ulist\"><ul><li><p>A Lisp</p></li><li><p>Functional</p></li></ul></div>
      </div>
    </div>
    <div class=\"sect1\">
      <h2>Immutability</h2>
      <div class=\"sectionbody\">
        <div class=\"paragraph\"><p>Data doesn't change.</p></div>
      </div>
    </div>
    <div class=\"clj-prev-next-container\"><a href=\"next\">Next</a></div>
  </div>
</body></html>"
      doc    (Jsoup/parse html)
      ;; Call the private extract-text via its var
      extract-text @(resolve 'seren.adapter.url-fetcher/extract-text)
      text   (extract-text doc)]
  (println "Extracted text:")
  (println text)
  (println "\n--- Chunking result ---")
  (let [chunks (content/chunk-by-heading text)]
    (println "Count:" (count chunks))
    (doseq [c chunks]
      (println (str "  [" (:index c) "] " (pr-str (:text c)))))))
;; Expected: TOC stripped, prev-next stripped.
;; 3+ chunks: preamble, "## Why Clojure?" section, "## Immutability" section

;; 4b: Test with actual clojure.org/about/rationale
(println "\n=== extract-text: live clojure.org ===")
(require '[seren.adapter.url-fetcher :as fetcher])
(let [result (fetcher/fetch-and-extract "https://clojure.org/about/rationale")]
  (if (:success result)
    (do
      (println "Title:" (:title result))
      (println "Text length:" (count (:text result)))
      (println "Contains \\n\\n?" (str/includes? (:text result) "\n\n"))
      (println "\\n\\n count:" (count (re-seq #"\n\n" (:text result))))
      (println "\nFirst 500 chars:")
      (println (subs (:text result) 0 (min 500 (count (:text result)))))
      ;; Now chunk it
      (println "\n--- Chunks ---")
      (let [chunks (content/chunk-by-heading (:text result))]
        (println "Count:" (count chunks))
        (doseq [c chunks]
          (println (str "  [" (:index c) "] "
                        (subs (:text c) 0 (min 80 (count (:text c)))) "...")))))
    (println "Fetch failed:" (:reason result))))

;; ============================================================
;; STEP 5: Full end-to-end via ingest (if server is running)
;; ============================================================
;; Only run this if you want to test the full pipeline via HTTP:
;;
;; curl -X POST http://localhost:8000/api/content \
;;   -H "Content-Type: application/json" \
;;   -d '{"url": "https://clojure.org/about/rationale"}'
;;
;; Check the chunk count in the response JSON.
