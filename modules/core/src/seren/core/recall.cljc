(ns seren.core.recall
  "Recall scoring — compares a user's free-recall transcript against source
   material to derive an SM-2 quality score automatically.

   The scoring pipeline:
   1. Tokenize both transcript and source text
   2. Compute token-level similarity (Jaccard index)
   3. Identify missed chunks (which parts of the source weren't recalled)
   4. Map similarity → SM-2 quality (0-5) via scheduler/similarity->quality

   All functions are pure — no IO, no atoms, no side effects.
   Token-level similarity (Jaccard) is used instead of character-level (Dice)
   because recall is about concepts/words, not spelling.

   See plan.md § 'Voice Free-Recall Flow'
   See: https://en.wikipedia.org/wiki/Jaccard_index"
  (:require [clojure.string :as str]
            [clojure.set :as cset]
            [malli.core :as m]
            [seren.core.schemas :as schemas]
            [seren.core.scheduler :as sched]))

;; --- Tokenization ---

;; Common English stop words — high-frequency words with little semantic value.
;; Removing these prevents false similarity matches (e.g. "is" inflating overlap).
;; See: https://en.wikipedia.org/wiki/Stop_word
(def ^:private stop-words
  #{"a" "an" "the" "is" "are" "was" "were" "be" "been" "being"
    "have" "has" "had" "do" "does" "did" "will" "would" "could"
    "should" "may" "might" "shall" "can" "need" "must"
    "am" "not" "no" "nor" "so" "if" "or" "and" "but" "yet"
    "at" "by" "for" "in" "of" "on" "to" "up" "out" "off"
    "from" "into" "over" "with" "as" "that" "this" "it" "its"})

(defn tokenize
  "Splits text into a sequence of lowercase word tokens.
   Strips punctuation, normalizes whitespace, and removes common English stop
   words. Returns [] for nil/blank input.

   Stop words are removed because recall scoring cares about concept coverage —
   shared function words like 'is' and 'the' would inflate similarity scores.
   See: https://nlp.stanford.edu/IR-book/html/htmledition/tokenization-1.html
   See: https://en.wikipedia.org/wiki/Stop_word"
  [text]
  (if (or (nil? text) (str/blank? text))
    []
    (-> text
        str/trim
        str/lower-case
        ;; Strip non-alphanumeric characters (keep hyphens inside words)
        ;; See: https://clojure.org/reference/other_functions#regex
        (str/replace #"[^\w\s-]" "")
        (str/split #"\s+")
        (->> (remove str/blank?)
             (remove stop-words)
             vec))))

(m/=> tokenize [:=> [:cat [:maybe :string]] [:vector :string]])

;; --- Similarity scoring ---

(defn token-similarity
  "Computes Jaccard similarity between two texts at the token level.
   Returns a double between 0.0 (no overlap) and 1.0 (identical token sets).

   Jaccard index = |A ∩ B| / |A ∪ B|
   This is order-independent — the user can recall concepts in any sequence.
   Duplicate tokens are collapsed (set-based comparison).

   See: https://en.wikipedia.org/wiki/Jaccard_index"
  [transcript source-text]
  (let [transcript-tokens (set (tokenize transcript))
        source-tokens     (set (tokenize source-text))
        intersection      (cset/intersection transcript-tokens source-tokens)
        union             (cset/union transcript-tokens source-tokens)]
    (if (empty? union)
      1.0  ;; Both empty — nothing to recall, nothing missed
      (double (/ (count intersection) (count union))))))

(m/=> token-similarity [:=> [:cat [:maybe :string] [:maybe :string]] :double])

;; --- Missed chunk detection ---

(defn find-missed-chunks
  "Identifies which content chunks were not adequately covered by the transcript.
   Returns a vector of missed Chunk maps, preserving their original order.

   A chunk is 'missed' if its token similarity with the transcript falls below
   the threshold (default 0.3). This means fewer than 30% of the chunk's
   distinctive words appeared in the recall.

   The threshold is intentionally low because a chunk might be 'covered'
   by paraphrasing — the user doesn't need to use the exact words.

   See plan.md § 'Voice Free-Recall Flow'"
  ([transcript chunks]
   (find-missed-chunks transcript chunks {}))
  ([transcript chunks {:keys [threshold] :or {threshold 0.3}}]
   (let [transcript-tokens (set (tokenize transcript))]
     (->> chunks
          (filterv
            (fn [chunk]
              (let [chunk-tokens (set (tokenize (:text chunk)))
                    overlap      (cset/intersection transcript-tokens chunk-tokens)]
                (if (empty? chunk-tokens)
                  false  ;; Empty chunk can't be missed
                  (< (double (/ (count overlap) (count chunk-tokens)))
                     threshold)))))))))

(m/=> find-missed-chunks
      [:=> [:cat [:maybe :string] [:vector schemas/Chunk]]
       [:vector schemas/Chunk]])

;; --- Top-level scoring ---

(defn score-recall
  "Scores a recall transcript against content, returning similarity, quality,
   and missed chunks. This is the main entry point for the scoring pipeline.

   Pipeline:
   1. token-similarity(transcript, source-text) → similarity (0.0-1.0)
   2. scheduler/similarity->quality(similarity) → quality (0-5)
   3. find-missed-chunks(transcript, chunks) → missed chunks

   The quality score feeds directly into SM-2 scheduling, replacing the
   manual self-assessment buttons from Phase 3."
  [transcript content]
  (let [similarity    (token-similarity transcript (:source-text content))
        quality       (sched/similarity->quality similarity)
        missed-chunks (find-missed-chunks transcript (:chunks content))]
    {:similarity    similarity
     :quality       quality
     :missed-chunks missed-chunks}))

(m/=> score-recall [:=> [:cat [:maybe :string] schemas/Content] schemas/RecallScore])

;; ---- REPL Examples ----
(comment
  ;; Tokenize text (stop words stripped)
  (tokenize "Clojure is a Functional Language!")
  ;; => ["clojure" "functional" "language"]

  ;; Compare transcript to source
  (token-similarity "Clojure is functional" "Clojure is a functional language")
  ;; => 0.6 (3 shared out of 5 unique tokens)

  ;; Find missed chunks
  (find-missed-chunks
    "Clojure is functional"
    [{:index 0 :text "Clojure is a functional language"}
     {:index 1 :text "Data is immutable"}])
  ;; => [{:index 1, :text "Data is immutable"}]

  ;; Full scoring pipeline
  (score-recall
    "Clojure is a functional language for the JVM"
    {:source-text "Clojure is a functional language for the JVM. It emphasises immutable data."
     :chunks [{:index 0 :text "Clojure is a functional language for the JVM"}
              {:index 1 :text "It emphasises immutable data"}]}))
