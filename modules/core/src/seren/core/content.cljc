(ns seren.core.content
  "Pure functions for content processing — normalisation, chunking,
   heading extraction, summarisation, and tag extraction.

   This module is the entry point for the ingestion pipeline. Raw text
   (or text fetched from a URL) passes through these functions to produce
   a structured Content entity ready for review scheduling.

   All functions are pure — no IO, no atoms, no side effects.
   See: https://www.learningscientists.org/blog/2016/8/18-1"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [seren.core.schemas :as schemas]
            [seren.schemas.common :as common]))

;; --- Validation ---

(defn validate-content-input
  "Validates that the input has at least a non-blank :text or :url.
   Returns a ValidationResult map.

   See: https://github.com/metosin/malli#validation"
  [input]
  (let [text (:text input)
        url  (:url input)]
    (cond
      (and (str/blank? text) (str/blank? url))
      {:valid? false :reason "Either :text or :url must be provided"}

      :else
      {:valid? true})))

;; Malli function schemas register the spec for runtime validation and CLI discovery.
;; See: https://github.com/metosin/malli#function-schemas
(m/=> validate-content-input
      [:=> [:cat schemas/ContentInput] common/ValidationResult])

;; --- Normalisation ---

(defn normalize-text
  "Normalises raw text: trims outer whitespace, collapses inline whitespace
   (spaces/tabs) to single spaces, but preserves paragraph breaks (double newlines).

   This is the first step in the ingestion pipeline — clean up messy
   copy-pasted text before chunking.

   See: https://clojure.org/reference/other_functions#regex"
  [text]
  (if (str/blank? text)
    ""
    (->> (str/split text #"\n\n+")                 ;; split on paragraph breaks
         (map #(-> % str/trim (str/replace #"[ \t]+" " "))) ;; collapse inline ws
         (remove str/blank?)                        ;; drop empty paragraphs
         (str/join "\n\n")                          ;; rejoin with double newline
         str/trim)))

(m/=> normalize-text [:=> [:cat :string] :string])

;; --- Chunking ---

(defn chunk-text
  "Splits normalised text into paragraph-based chunks.
   Each chunk is a map with :index (0-based position) and :text.

   Chunks are the unit of review in Seren — each one is independently
   scored during free-recall sessions.

   See: https://clojure.org/reference/sequences#_seq_in_seq_out"
  [text]
  (if (str/blank? text)
    []
    (let [normalized (normalize-text text)
          paragraphs (str/split normalized #"\n\n+")]
      (->> paragraphs
           (remove str/blank?)
           (map-indexed (fn [i para]
                          {:index i
                           :text  (str/trim para)}))
           vec))))

(m/=> chunk-text [:=> [:cat :string] [:vector schemas/Chunk]])

;; --- Heading extraction ---

(defn extract-headings
  "Extracts markdown-style headings (# h1, ## h2, etc.) from text.
   Returns a vector of Heading maps with :level and :text.

   Headings serve two purposes in Seren:
   1. \"Headings only\" review shape — tests high-level recall
   2. Title derivation when no explicit title is provided

   See: https://spec.commonmark.org/0.31.2/#atx-headings"
  [text]
  (if (str/blank? text)
    []
    (->> (str/split-lines text)
         (keep (fn [line]
                 (when-let [[_ hashes heading-text] (re-matches #"^(#{1,6})\s+(.+)$" (str/trim line))]
                   {:level (count hashes)
                    :text  (str/trim heading-text)})))
         vec)))

(m/=> extract-headings [:=> [:cat :string] [:vector schemas/Heading]])

;; --- Summarisation ---

;; Stopwords for English — common words excluded from tags and summaries.
;; Keep this small and functional; a more complete set could be loaded from
;; a resource file later.
;; See: https://en.wikipedia.org/wiki/Stop_word
(def ^:private stopwords
  #{"a" "an" "the" "is" "are" "was" "were" "be" "been" "being"
    "have" "has" "had" "do" "does" "did" "will" "would" "shall"
    "should" "may" "might" "must" "can" "could" "and" "but" "or"
    "nor" "not" "so" "yet" "both" "either" "neither" "each" "every"
    "all" "any" "few" "more" "most" "other" "some" "such" "no"
    "only" "own" "same" "than" "too" "very" "just" "because"
    "as" "until" "while" "of" "at" "by" "for" "with" "about"
    "against" "between" "through" "during" "before" "after" "above"
    "below" "to" "from" "up" "down" "in" "out" "on" "off" "over"
    "under" "again" "further" "then" "once" "here" "there" "when"
    "where" "why" "how" "what" "which" "who" "whom" "this" "that"
    "these" "those" "i" "me" "my" "myself" "we" "our" "ours"
    "you" "your" "yours" "he" "him" "his" "she" "her" "hers"
    "it" "its" "they" "them" "their" "theirs"})

(defn summarize
  "Produces a summary by taking the first sentence or two from the text.
   For short text (under 100 chars), returns it as-is.

   This is a simple extractive summary — a more sophisticated approach
   (LLM-based or TF-IDF) can be layered on via the Adapter later.

   See: https://en.wikipedia.org/wiki/Automatic_summarization#Extraction-based"
  [text]
  (if (or (str/blank? text) (<= (count text) 100))
    (str/trim (or text ""))
    ;; Take content up to the second sentence-ending punctuation
    (let [;; Remove markdown headings for cleaner summary
          clean (str/replace text #"(?m)^#{1,6}\s+" "")
          ;; Split into sentences on . ! ? followed by space or end
          sentences (re-seq #"[^.!?]+[.!?]?" clean)
          ;; Take first two sentences
          summary (->> sentences
                       (take 2)
                       (str/join " ")
                       str/trim)]
      (if (str/blank? summary)
        (subs (str/trim clean) 0 (min 100 (count (str/trim clean))))
        summary))))

(m/=> summarize [:=> [:cat :string] :string])

;; --- Tag extraction ---

(defn extract-tags
  "Extracts significant words from text as lowercase tags.
   Filters out stopwords and short words (< 3 chars).
   Returns the top tags by frequency.

   Tags enable the \"content connections\" feature in Phase 6 —
   finding semantic overlap between different content items.

   See: https://en.wikipedia.org/wiki/Tf%E2%80%93idf"
  [text]
  (if (str/blank? text)
    []
    (let [;; Remove markdown heading markers
          clean (str/replace text #"(?m)^#{1,6}\s+" "")
          words (str/split (str/lower-case clean) #"[^a-z0-9-]+")
          significant (->> words
                           (remove str/blank?)
                           (remove #(< (count %) 3))
                           (remove stopwords))]
      (->> significant
           frequencies
           (sort-by val >)
           (take 10)
           (mapv first)))))

(m/=> extract-tags [:=> [:cat :string] [:vector :string]])

;; --- Content processing pipeline ---

(defn process-content
  "The main ingestion pipeline: takes a ContentInput and produces a Content entity.

   Steps:
   1. Normalise the text
   2. Extract headings (for review shapes and title fallback)
   3. Chunk into paragraphs (the unit of review)
   4. Summarise (extractive, first 2 sentences)
   5. Extract tags (significant words by frequency)
   6. Derive title: explicit > first heading > first 50 chars

   This is a pure function — all side effects (fetching URLs, persisting)
   happen in the Adapter and App layers."
  [{:keys [text title url] :as _input}]
  (let [source    (normalize-text (or text ""))
        headings  (extract-headings (or text ""))
        chunks    (chunk-text source)
        summary   (summarize source)
        tags      (extract-tags source)
        ;; Title derivation: explicit title > first heading > truncated text
        derived-title (or (when-not (str/blank? title) (str/trim title))
                          (when (seq headings) (:text (first headings)))
                          (let [t (subs source 0 (min 50 (count source)))]
                            (if (str/blank? t) "Untitled" t)))]
    {:id         (str
                   #?(:clj  (java.util.UUID/randomUUID)
                      :cljs (random-uuid)))
     :url        url
     :title      derived-title
     :source-text source
     :chunks     chunks
     :headings   headings
     :summary    summary
     :tags       tags
     :created-at #?(:clj  (System/currentTimeMillis)
                    :cljs  (.getTime (js/Date.)))}))

(m/=> process-content [:=> [:cat schemas/ContentInput] schemas/Content])

;; ---- REPL Examples ----
(comment
  ;; Validate input
  (validate-content-input {:text "Hello world"})
  ;; => {:valid? true}

  (validate-content-input {})
  ;; => {:valid? false, :reason "Either :text or :url must be provided"}

  ;; Normalize messy text
  (normalize-text "  hello   world  \n\n  second  paragraph  ")
  ;; => "hello world\n\nsecond paragraph"

  ;; Chunk into paragraphs
  (chunk-text "Para one.\n\nPara two.\n\nPara three.")
  ;; => [{:index 0 :text "Para one."} {:index 1 :text "Para two."} ...]

  ;; Extract headings
  (extract-headings "# Title\n\nBody\n\n## Section")
  ;; => [{:level 1 :text "Title"} {:level 2 :text "Section"}]

  ;; Full pipeline
  (process-content {:text "# Clojure\n\nA functional language.\n\n## Immutability\n\nData is immutable."
                    :title "Clojure Guide"})
  )
