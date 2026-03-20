# Phase 4.5 — URL Fetching & Content Extraction

> *"As a learner I want to send Seren a URL and have it extract the article for me"*

This phase sits between Phase 4 (Voice + Scoring) and Phase 5 (PWA/Notifications).
It makes the existing ingestion pipeline dramatically more useful by letting the
user send just a URL — the backend fetches the page and extracts readable content.

---

## Why This Matters

Today, `POST /api/ingest` accepts `{:text :title :url}` but `:text` is required
for anything meaningful to happen. The `:url` field is stored but never fetched.
This means every ingestion path — manual paste, future extension, future share
target — requires the *client* to scrape and send the full article text.

With server-side URL fetching:
- Manual paste in the PWA: paste a URL, done
- Chrome extension (e.g. "Save URL to Remote API"): one click, sends `{"url": "..."}`
- Bookmarklet: one click, sends the URL
- Future mobile share target: receives URL from share sheet, forwards it
- Future RSS polling: feed entries are just URLs

One backend change unlocks every ingestion path.

---

## Design

### Principles

1. **Adapter layer only** — Fetching HTML is a side effect. It belongs in
   `adapter/`, not `core/`. The core pipeline (`process-content`) stays pure.
2. **Jsoup on the JVM** — Jsoup handles both HTTP fetching *and* readable text
   extraction (`.text()` strips tags, scripts, styles). No need for a separate
   Readability port. Direct Java interop keeps dependencies minimal.
3. **Graceful degradation** — If URL fetching fails (network error, paywall,
   non-HTML), return a clear error. Never silently produce empty content.
4. **`:text` still wins** — If the client sends both `:text` and `:url`, use
   the provided text. URL fetching is a fallback for when only a URL is given.

### Flow Change

```
BEFORE:
  Client sends {:text "..." :url "..." :title "..."}
  → validate (needs :text) → process-content → store → review

AFTER:
  Client sends {:url "https://..." :title "..."}
  → resolve-content (new, adapter)
    → URL provided but no text? fetch + extract → {:text "..." :title "..." :url "..."}
    → text already provided? pass through unchanged
  → validate → process-content → store → review
```

The new `resolve-content` step is injected in `app/main.cljc`'s `ingest-content!`
function, *before* validation and processing.

---

## Implementation

### 1. Add Jsoup dependency

**File**: `modules/adapter/deps.edn`

```clojure
{:deps {org.jsoup/jsoup {:mvn/version "1.18.3"}}}
```

Jsoup is a single JAR, no transitive dependencies. It handles:
- HTTP GET with configurable timeout, user-agent, redirects
- HTML parsing (lenient, handles real-world markup)
- Text extraction (`.text()` on elements, strips scripts/styles)
- CSS selectors for targeting `<article>`, `<main>`, `<body>`

> See: https://jsoup.org/

### 2. New adapter: `url_fetcher.clj`

**File**: `modules/adapter/src/seren/adapter/url_fetcher.clj`

```clojure
(ns seren.adapter.url-fetcher
  "Fetches a URL and extracts readable text content using Jsoup.
   JVM-only — URL fetching is a server-side concern.
   See: https://jsoup.org/cookbook/extracting-data/selector-syntax"
  (:require [malli.core :as m]
            [seren.core.schemas :as schemas])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Document]))
```

**Public functions**:

#### `fetch-and-extract`
```
[:=> [:cat :string] FetchResult]
```

Takes a URL string. Returns a result map:
```clojure
;; Success:
{:success true
 :text "extracted article text..."
 :title "Page Title"
 :url "https://example.com/article"}

;; Failure:
{:success false
 :reason "Connection timed out: https://example.com/article"}
```

**Extraction strategy** (ordered by specificity):
1. Try `<article>` element → `.text()`
2. Try `<main>` element → `.text()`
3. Try `[role=main]` element → `.text()`
4. Fall back to `<body>` → `.text()`

**Title extraction** (first non-blank wins):
1. `<meta property="og:title">` (Open Graph — most accurate for articles)
2. `<title>` element
3. First `<h1>` element

**Metadata extraction** — pulled from `<meta>` tags into a `:meta` map:
- `<meta name="author">` or `<meta property="og:author">` → `:author`
- `<meta name="description">` or `<meta property="og:description">` → `:description`
- `<meta property="og:site_name">` → `:site-name`
- `<meta property="article:published_time">` → `:published-at`
- `<meta property="og:image">` → `:image-url`

Only non-blank values are included. The `:meta` map is omitted entirely if
all fields are blank (page has no useful metadata).

**Configuration**:
- Timeout: 10 seconds
- User-Agent: `"Seren/1.0 (local learning assistant)"`
- Max body size: 2MB (skip huge pages)
- Follow redirects: yes

**Error handling** — catch and wrap:
- `java.net.SocketTimeoutException` → `"Connection timed out"`
- `org.jsoup.HttpStatusException` → `"HTTP 403: Forbidden"` (etc.)
- `java.net.MalformedURLException` → `"Invalid URL"`
- `java.net.UnknownHostException` → `"Could not resolve host"`
- Generic `Exception` → `"Fetch failed: <message>"`

**Minimum content threshold**: If extracted text is < 50 characters after
stripping, return a failure that tells the user what to do next:
```clojure
{:success false
 :reason "thin-content"
 :message "This page didn't have enough readable text — it may require
           JavaScript or a login. Try copying the article text and pasting
           it directly instead."}
```
The `:reason` keyword lets the UI distinguish thin-content from network errors
and show an appropriate prompt (e.g. expand the paste field, pre-fill the URL).
This catches login walls, JavaScript-only SPAs, and paywalled articles.

### 3. New schemas

**File**: `modules/core/src/seren/core/schemas.cljc`

```clojure
;; Metadata extracted from <meta> and Open Graph tags at fetch time.
;; Optional — only present when content was ingested from a URL.
;; See: https://ogp.me/
(def ContentMeta
  [:map
   [:author {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:site-name {:optional true} [:maybe :string]]
   [:published-at {:optional true} [:maybe :string]]
   [:image-url {:optional true} [:maybe Url]]])

;; Result of URL fetching — success carries extracted text + metadata,
;; failure carries reason.
;; See: https://github.com/metosin/malli#value-schemas
(def FetchResult
  [:or
   [:map
    [:success [:= true]]
    [:text common/NonBlankString]
    [:title :string]
    [:url Url]
    [:meta {:optional true} ContentMeta]]
   [:map
    [:success [:= false]]
    [:reason :string]]])
```

The `Content` schema gains an optional `:meta` key:
```clojure
[:meta {:optional true} [:maybe ContentMeta]]
```

### 4. Update `app/main.cljc` — Wire in URL resolution

**File**: `modules/app/src/seren/app/main.cljc`

Add a `resolve-content-input` step to `ingest-content!`:

```clojure
(defn ingest-content!
  "Ingest content from text, URL, or both. If only a URL is provided,
   fetches and extracts the page content server-side."
  [{:keys [store-dir review-dir] :as config}
   {:keys [text url title] :as input}]
  (let [;; If URL provided but no text, fetch it (JVM only)
        resolved (if (and (not (str/blank? url))
                          (str/blank? text))
                   #?(:clj  (let [result (url-fetcher/fetch-and-extract url)]
                              (if (:success result)
                                {:text  (:text result)
                                 :title (or title (:title result))
                                 :url   url}
                                {:error (:reason result)}))
                      :cljs {:error "URL fetching not supported in browser"})
                   ;; Text provided — use as-is
                   input)]
    (if (:error resolved)
      {:success false :reason (:error resolved)}
      ;; ... existing validate → process → store → review pipeline
      )))
```

This preserves the existing flow: if `:text` is provided, nothing changes.
URL fetching only kicks in when `:url` is present and `:text` is blank.

### 5. Update `POST /api/ingest` — Accept JSON

**File**: `modules/app/src/seren/app/server.clj`

The endpoint currently reads EDN. For Chrome extensions to work easily, it
should also accept JSON:

```clojure
(defn read-body
  "Read request body as EDN or JSON based on Content-Type.
   Falls back to EDN for backwards compatibility.
   See: https://github.com/ring-clojure/ring/wiki/Concepts#requests"
  [request]
  (let [content-type (get-in request [:headers "content-type"] "")]
    (if (str/includes? content-type "application/json")
      (-> request :body slurp json/read-str keyword-keys)
      (read-edn-body request))))
```

This means a Chrome extension can POST:
```json
{"url": "https://example.com/article"}
```

with `Content-Type: application/json` and it just works.

### 6. Update PWA library page

**File**: `modules/app/src/seren/app/pages/library.clj` + `islands/library.cljs`

The paste field currently expects text. Update it to:
- Detect if the input looks like a URL (starts with `http://` or `https://`)
- If URL: send `{:url input}` (server fetches)
- If text: send `{:text input}` (existing behaviour)
- Show a loading spinner while fetching (URL extraction takes a few seconds)
- Show the extracted title + text preview on success

### 7. Bookmarklet

No server changes needed. Generate a bookmarklet the user can drag to their
bookmark bar. Served from a static page at `/bookmarklet`:

```javascript
javascript:void(fetch('http://localhost:3000/api/ingest',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:location.href,title:document.title})}).then(r=>r.json()).then(d=>alert(d.success?'Saved to Seren!':'Error: '+d.reason)).catch(e=>alert('Seren offline')))
```

This is a single line — no extension, no install, works in every browser.

---

## Task Checklist

- [ ] Add `org.jsoup/jsoup` to adapter `deps.edn`
- [ ] `core/schemas.cljc` — `FetchResult`, `ContentMeta` schemas; add `:meta` to `Content`
- [ ] `adapter/url_fetcher.clj` — `fetch-and-extract` with Jsoup (text + title + metadata)
- [ ] `adapter/content_store.clj` — `find-by-url` query for duplicate detection
- [ ] **Tests**: `url_fetcher` integration tests (real HTTP to known URLs + error cases)
- [ ] **Tests**: `find-by-url` returns existing content or nil
- [ ] `app/main.cljc` — wire `resolve-content-input` into `ingest-content!` with duplicate check
- [ ] `app/server.clj` — accept JSON in addition to EDN on `/api/ingest`
- [ ] **Tests**: ingest with URL-only payload end-to-end
- [ ] **Tests**: duplicate URL returns existing content with `:existing true` flag
- [ ] Update PWA library page — URL detection, loading state, "already saved" nudge
- [ ] `/bookmarklet` page with drag-to-install instructions
- [ ] **Tests**: bookmarklet payload format matches API expectations

---

## Clojure Concepts Introduced

| Concept | Where |
|---|---|
| Java interop (`.method`, `Classname.`, `import`) | `url_fetcher.clj` — Jsoup calls |
| Exception handling (`try`/`catch`, exception types) | `url_fetcher.clj` — network errors |
| Conditional compilation (`#?(:clj :cljs)`) | `app/main.cljc` — URL fetching JVM-only |
| Content negotiation (EDN vs JSON) | `server.clj` — multi-format body reading |

---

## Extension Compatibility

Once this is deployed, any tool that can POST JSON to a URL works as an
ingestion source:

| Tool | Config needed |
|---|---|
| Save URL to Remote API (Chrome) | Set URL to `http://localhost:3000/api/ingest` |
| Send to Webhook (Chrome) | Body template: `{"url": "{{content}}"}`, Content-Type: `application/json` |
| Webhook Manager (Chrome) | Endpoint URL + JSON payload |
| Bookmarklet | Drag to bookmark bar, done |
| iOS Shortcut | "Get URL" → "POST to URL" action |
| Android Tasker | HTTP POST task |
| curl (testing) | `curl -X POST -H 'Content-Type: application/json' -d '{"url":"..."}' localhost:3000/api/ingest` |

---

## Design Decisions (Resolved)

1. **JavaScript-heavy pages** — Jsoup fetches raw HTML, no JS execution. If
   extraction yields < 50 characters of readable text, the API returns a clear
   message prompting the user to copy-paste the content manually instead. No
   headless browser fallback for v1 — most articles are server-rendered, and
   the manual paste path already exists as a natural fallback.

2. **Duplicate URLs** — A repeated URL is a learning signal, not an error. When
   the user sends a URL that already exists in the content store:
   - Do **not** re-fetch or create a duplicate content item
   - Return the existing content item with a flag: `{:success true :existing true :content ...}`
   - The UI can surface this helpfully: *"You saved this 12 days ago. You have
     a review due — want to start it now?"* — nudging the user back into the
     review loop rather than re-ingesting
   - Implementation: `content_store/find-by-url` query before fetching

3. **Rate limiting** — Not needed for a single-user local tool. Revisit only if
   RSS feed polling is added later.

4. **Metadata extraction** — Capture useful metadata at fetch time and store it
   alongside the content. Jsoup can pull Open Graph and standard `<meta>` tags:

   | Meta field | Source | Schema key |
   |---|---|---|
   | Author | `<meta name="author">` or `og:author` | `:author` |
   | Published date | `<meta property="article:published_time">` | `:published-at` |
   | Description | `<meta name="description">` or `og:description` | `:description` |
   | Site name | `<meta property="og:site_name">` | `:site-name` |
   | Image | `<meta property="og:image">` | `:image-url` |

   These go into a new `:meta` map on the `Content` schema — optional, present
   only when the source was a fetched URL. Useful for richer content cards in
   the library view and for future features (e.g. grouping by source site).