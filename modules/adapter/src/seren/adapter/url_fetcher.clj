(ns seren.adapter.url-fetcher
  "JVM adapter for fetching URLs and extracting readable article text.

   Uses Jsoup for both HTTP fetching and HTML parsing/extraction.
   This is a side-effectful module (network I/O) — it belongs in the
   Adapter layer, not Core.

   Extraction strategy (ordered by specificity):
   1. <article> element
   2. <main> element
   3. [role=main] attribute
   4. <body> fallback

   Returns a FetchResult map — see seren.core.schemas/FetchResult.

   See: https://jsoup.org/cookbook/extracting-data/selector-syntax
   See: https://jsoup.org/apidocs/org/jsoup/Jsoup.html"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [seren.core.schemas :as schemas])
  (:import [org.jsoup Jsoup HttpStatusException]
           [org.jsoup.nodes Document Element]
           [java.net SocketTimeoutException MalformedURLException UnknownHostException]))

;; --- Configuration ---
;; These are sensible defaults for a local learning tool fetching articles.
;; See: https://jsoup.org/apidocs/org/jsoup/Connection.html

(def ^:private fetch-timeout-ms
  "HTTP connection + read timeout in milliseconds."
  10000)

(def ^:private max-body-size-bytes
  "Maximum response body size (2MB). Prevents OOM on huge pages."
  (* 2 1024 1024))

(def ^:private user-agent
  "User-Agent string sent with fetch requests."
  "Seren/1.0 (local learning assistant)")

(def ^:private min-content-length
  "Minimum extracted text length to consider a page 'readable'.
   Below this threshold, we assume the page is JS-rendered, paywalled,
   or otherwise not extractable."
  50)

;; --- Internal helpers ---

;; CSS selector that matches all block-level content elements.
;; We select these as direct extraction targets — each one's .text()
;; becomes a separate paragraph in the output, joined by \n\n.
;; See: https://developer.mozilla.org/en-US/docs/Glossary/Block-level_content
(def ^:private block-selector
  "p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, dt, dd, figcaption")

(defn- element->paragraph
  "Converts a single block-level HTML element to a text paragraph.
   Headings get a markdown prefix (# / ## / ###) so Core's
   extract-headings works on fetched content the same as pasted markdown.
   Returns nil for blank elements."
  [^Element el]
  (let [tag  (str/lower-case (.tagName el))
        text (.text el)]
    (when-not (str/blank? text)
      (if (re-matches #"h[1-6]" tag)
        (let [level  (parse-long (subs tag 1))
              prefix (apply str (repeat level "#"))]
          (str prefix " " text))
        text))))

(defn- strip-noise!
  "Removes non-content elements (nav, TOC, footer, sidebar) from a container
   before extraction. Mutates the Jsoup Element in place.
   See: https://jsoup.org/cookbook/modifying-data/set-html"
  [^Element container]
  (doseq [sel ["nav" "header" "footer" "aside"
               ".toc" "#toc" "[role=navigation]"
               ".clj-prev-next-container"  ;; clojure.org specific
               ".sidebar" ".nav"]]
    (doseq [^Element el (.select container sel)]
      (.remove el)))
  container)

(defn- extract-text
  "Extracts readable text from an HTML document, preserving paragraph structure.

   Strategy:
   1. Find the best content container, trying specific selectors first
      (common CMS wrappers like .clj-content-container, #content) before
      falling back to semantic HTML (article, main) and finally body.
   2. Strip navigation, TOC, footer, and sidebar elements.
   3. Select all block-level children (p, h1-h6, li, blockquote, etc.)
   4. Convert each to text (headings get markdown # prefixes).
   5. Join with \\n\\n to create paragraph boundaries for Core's chunking.

   See: https://jsoup.org/cookbook/extracting-data/selector-syntax"
  [^Document doc]
  ;; Ordered from most-specific (CMS wrappers) to least-specific (body).
  ;; Many static-site generators (Asciidoctor, Jekyll, Hugo) wrap content
  ;; in a div with a class or id rather than using <article>/<main>.
  (let [selectors [".clj-content-container"      ;; clojure.org (Asciidoctor)
                   "#content" ".content"           ;; common CMS patterns
                   "[role=main]"                   ;; ARIA landmark
                   "article" "main"                ;; semantic HTML5
                   "body"]                         ;; last resort
        try-selector
        (fn [sel]
          (when-let [^Element container (.selectFirst doc sel)]
            ;; Clone so we don't mutate the original doc — extract-title
            ;; and extract-metadata may still need the full DOM.
            (let [clean     (strip-noise! (.clone container))
                  blocks    (.select clean block-selector)
                  paragraphs (->> blocks
                                  (map element->paragraph)
                                  (remove nil?)
                                  vec)]
              (when (seq paragraphs)
                (str/join "\n\n" paragraphs)))))]
    (some try-selector selectors)))

(defn- extract-title
  "Extracts the page title using a priority cascade:
   1. Open Graph og:title (most accurate for articles)
   2. <title> element
   3. First <h1> element

   See: https://ogp.me/"
  [^Document doc]
  (let [og-title (some-> (.selectFirst doc "meta[property=og:title]")
                         (.attr "content"))
        html-title (.title doc)
        h1-title (some-> (.selectFirst doc "h1")
                         (.text))]
    (first (remove str/blank? [og-title html-title h1-title]))))

(defn- extract-meta-attr
  "Extracts a content attribute from a meta tag by selector.
   Returns nil if not found or blank."
  [^Document doc selector]
  (when-let [^Element el (.selectFirst doc selector)]
    (let [content (.attr el "content")]
      (when-not (str/blank? content)
        content))))

(defn- extract-metadata
  "Extracts Open Graph and standard <meta> tag metadata from the document.
   Returns a ContentMeta map with only non-nil values, or nil if no
   metadata was found.

   See: https://ogp.me/
   See: https://developer.mozilla.org/en-US/docs/Web/HTML/Element/meta"
  [^Document doc]
  (let [author       (or (extract-meta-attr doc "meta[name=author]")
                         (extract-meta-attr doc "meta[property=og:author]"))
        description  (or (extract-meta-attr doc "meta[name=description]")
                         (extract-meta-attr doc "meta[property=og:description]"))
        site-name    (extract-meta-attr doc "meta[property=og:site_name]")
        published-at (extract-meta-attr doc "meta[property=article:published_time]")
        image-url    (extract-meta-attr doc "meta[property=og:image]")
        meta-map     (cond-> {}
                       author       (assoc :author author)
                       description  (assoc :description description)
                       site-name    (assoc :site-name site-name)
                       published-at (assoc :published-at published-at)
                       image-url    (assoc :image-url image-url))]
    (when (seq meta-map)
      meta-map)))

;; --- Public API ---

(defn fetch-and-extract
  "Fetches a URL and extracts readable text content.

   Returns a FetchResult map:
   - Success: {:success true :text \"...\" :title \"...\" :url \"...\" :meta {...}}
   - Failure: {:success false :reason \"...\" :message \"...\"}

   The :meta key is only present when the page has extractable OG/meta tags.
   On thin content (< 50 chars), returns a failure with :reason \"thin-content\"
   so the UI can prompt the user to paste manually instead.

   See: https://jsoup.org/cookbook/input/load-document-from-url"
  [url]
  (try
    (let [^Document doc (-> (Jsoup/connect url)
                            (.userAgent user-agent)
                            (.timeout fetch-timeout-ms)
                            (.maxBodySize max-body-size-bytes)
                            (.followRedirects true)
                            (.get))
          text          (extract-text doc)
          title         (or (extract-title doc) "")
          metadata      (extract-metadata doc)]
      (if (or (nil? text) (< (count text) min-content-length))
        {:success false
         :reason  "thin-content"
         :message (str "This page didn't have enough readable text — it may "
                       "require JavaScript or a login. Try copying the article "
                       "text and pasting it directly instead.")}
        (cond-> {:success true
                 :text    text
                 :title   title
                 :url     url}
          metadata (assoc :meta metadata))))
    ;; Catch specific exception types for clear error messages.
    ;; See: https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/package-summary.html
    (catch MalformedURLException _
      {:success false
       :reason  (str "Invalid URL: " url)})
    (catch UnknownHostException _
      {:success false
       :reason  (str "Could not resolve host: " url)})
    (catch SocketTimeoutException _
      {:success false
       :reason  (str "Connection timed out: " url)})
    (catch HttpStatusException e
      {:success false
       :reason  (str "HTTP " (.getStatusCode e) ": " (.getMessage e))})
    (catch Exception e
      {:success false
       :reason  (str "Fetch failed: " (.getMessage e))})))

(m/=> fetch-and-extract [:=> [:cat :string] schemas/FetchResult])

(comment
  ;; Fetch a real article
  (fetch-and-extract "https://clojure.org/about/rationale")

  ;; Fetch a page that will likely have thin content (JS-heavy)
  (fetch-and-extract "https://app.example.com/dashboard")

  ;; Invalid URL
  (fetch-and-extract "not-a-url"))
