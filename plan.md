# Seren — Learning Agent Plan

> **Seren** (Welsh: *star*) — an ever-present learning companion that surfaces
> what you've consumed and guides you back through it via voice-driven free recall.

## Vision

A local-network PWA that captures content you consume online, schedules spaced
retrieval practice, and lets you **speak** what you remember. Your voice transcript
is compared against the source material to score recall and highlight gaps — no
flashcard authoring required.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Your Home Network                            │
│                                                                      │
│  INGESTION PATHS                    JVM BACKEND (Ring + Reitit)      │
│  ════════════════                   ════════════════════════════      │
│                                                                      │
│  ┌──────────────┐                  ┌──────────────────────────────┐  │
│  │ 1. PWA       │── POST /api/ ──►│                              │  │
│  │ Manual Entry │   ingest         │  ┌─────────┐ ┌───────────┐  │  │
│  │ (paste URL   │   {url}          │  │   App   │ │ PocketBase│  │  │
│  │  or text)    │                  │  │ (wiring)│ │ (persists │  │  │
│  └──────────────┘                  │  │         │ │  content, │  │  │
│                                    │  │    ▼    │ │  reviews, │  │  │
│  ┌──────────────┐                  │  │┌───────┐│ │  scores)  │  │  │
│  │ 2. Mobile    │── PWA Web ─────►│  ││ Core  ││ └───────────┘  │  │
│  │ Share Target │   Share API      │  ││(pure) ││       ▲        │  │
│  │ ("Share to   │   {url, title}   │  │└───────┘│       │        │  │
│  │  Seren")     │                  │  │    ▼    │       │        │  │
│  └──────────────┘                  │  │┌───────┐│───────┘        │  │
│                                    │  ││Adapter││                │  │
│  ┌──────────────┐                  │  │└───────┘│                │  │
│  │ 3. Browser   │── POST /api/ ──►│  └─────────┘                │  │
│  │ Extension    │   ingest         │                              │  │
│  │ (separate    │   {url, title,   └──────────────────────────────┘  │
│  │  project,    │    html}                                           │
│  │  later phase)│                                                    │
│  └──────────────┘                  ┌──────────────────────────────┐  │
│                                    │         PWA Client           │  │
│                                    │  • Voice free-recall UI      │  │
│                                    │  • Review session pages      │  │
│                                    │  • Content library           │  │
│                                    │  • Score dashboard           │  │
│                                    │  • Push notifications        │  │
│                                    └──────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

### Content Ingestion Paths (ordered by implementation priority)

All three paths converge on the same `POST /api/ingest` endpoint. The backend
handles fetching, extracting, chunking, and scheduling — ingestion clients are
deliberately thin.

**Path 1 — PWA Manual Entry (Phase 1)**
Paste a URL or raw text directly in the PWA library page. Simplest path, no
extra projects, gets us end-to-end ingestion immediately.

**Path 2 — Mobile Web Share Target (Phase 5)**
The PWA manifest declares a [Web Share Target](https://developer.mozilla.org/en-US/docs/Web/Manifest/share_target),
so mobile browsers offer "Share to Seren" alongside native apps. This is nearly
free once the PWA manifest and service worker are in place — just a manifest
field and a route handler.

**Path 3 — Browser Extension (Phase 7, separate project)**
A lightweight Manifest V3 extension (~3 files of vanilla JS) with a context
menu item "Send to Seren". It scrapes `document.body.innerText` and POSTs to
`/api/ingest`. This lives in its own repo — the API contract is the boundary,
no shared code needed.

---

## Learning Science Foundation

We ground every feature in the [Six Strategies for Effective Learning](https://www.learningscientists.org/blog/2016/8/18-1):

| Strategy | How Seren applies it |
|---|---|
| **Retrieval Practice** | Voice free-recall sessions — no peeking at source material |
| **Spaced Practice** | SM-2–inspired scheduling spreads reviews over increasing intervals |
| **Elaboration** | Post-recall, user sees gaps and is prompted: *"Can you explain why X matters?"* |
| **Interleaving** | Review queue mixes content from different topics/sources |
| **Concrete Examples** | Content is chunked to highlight examples and key passages |
| **Dual Coding** | Content shown as headings-only, keyword-blanks, and summaries alongside original |

---

## FCIS Module Map

```
modules/
├── core/src/seren/core/           ← Pure .cljc (JVM + JS)
│   ├── content.cljc               ← Content normalisation, chunking, summarisation
│   ├── recall.cljc                ← Similarity scoring, gap detection
│   ├── scheduler.cljc             ← SM-2 interval calculations
│   ├── review.cljc                ← Review shape generators (headings, blanks, summary)
│   └── schemas.cljc               ← All domain schemas
│
├── adapter/src/seren/adapter/     ← Side effects
│   ├── content_store.clj/.cljs    ← CRUD for ingested content (PocketBase)
│   ├── review_store.clj/.cljs     ← Review history persistence
│   ├── notifier.clj               ← PWA push notifications (web-push, JVM only)
│   └── speech.cljs                ← Web Speech API wrapper (browser only)
│
└── app/src/seren/app/             ← Wiring
    ├── main.cljc                 ← Orchestration (ingest → schedule → review → score)
    ├── server.clj                ← Ring routes: /api/ingest, /api/review, /api/score
    ├── pages/                    ← Server-rendered Hiccup
    │   ├── library.clj           ← Content library view
    │   ├── review.clj            ← Review session page
    │   └── dashboard.clj         ← Score history, upcoming reviews
    └── islands/                  ← CLJS interactivity
        ├── voice.cljs            ← Microphone capture + SpeechRecognition
        ├── review.cljs           ← Interactive review flow
        └── library.cljs          ← Content browsing + manual link entry
```

---

## Data Model (PocketBase Collections)

```
┌─────────────┐       ┌──────────────┐       ┌──────────────┐
│   content    │       │   reviews    │       │   scores     │
├─────────────┤       ├──────────────┤       ├──────────────┤
│ id           │──┐    │ id           │──┐    │ id           │
│ url          │  │    │ content_id ──│──┘    │ review_id ───│──┐
│ title        │  │    │ due_at       │       │ similarity   │  │
│ source_text  │  │    │ interval     │       │ transcript   │  │
│ chunks[]     │  │    │ ease_factor  │       │ missed_chunks│  │
│ headings[]   │  │    │ repetitions  │       │ created_at   │  │
│ summary      │  └────│──────────────│       └──────────────┘  │
│ tags[]       │       │ status       │              ▲           │
│ created_at   │       │ shape        │              │           │
└─────────────┘       └──────────────┘              └───────────┘
```

---

## SM-2 Scheduling (Core, Pure)

The scheduler lives entirely in `core/scheduler.cljc` — a pure function that takes
the previous review state and a quality score (0–5) and returns the next state:

```
┌──────────┐    quality     ┌──────────────┐    {:interval :ease-factor
│  User    │───(0-5)──────► │  next-review  │     :repetitions :due-at}
│  Score   │                │  (pure fn)    │───────────────────────►
└──────────┘                └──────────────┘
```

**Quality mapping** — rather than the user self-grading (like Anki), Seren derives
quality from the **similarity score** between transcript and source:

| Similarity | Quality | Meaning |
|---|---|---|
| ≥ 0.90 | 5 | Perfect recall |
| ≥ 0.75 | 4 | Correct with hesitation |
| ≥ 0.55 | 3 | Correct with difficulty |
| ≥ 0.35 | 2 | Incorrect but familiar |
| ≥ 0.15 | 1 | Barely remembered |
| < 0.15 | 0 | Total blackout |

---

## Voice Free-Recall Flow

```
  ┌─────────┐   tap    ┌────────────┐  audio  ┌─────────────────┐
  │  PWA    │ ──────►  │  Record    │ ──────► │ SpeechRecognition│
  │  Review │  "start" │  (island)  │ stream  │ (Web Speech API) │
  │  Page   │          └────────────┘         └────────┬────────┘
  └─────────┘                                          │
                                                 transcript
                                                       │
                                                       ▼
  ┌──────────────────┐  POST /api/score   ┌─────────────────────┐
  │  Score Display   │ ◄──────────────── │   JVM Backend        │
  │  • similarity %  │                    │   core/recall.cljc   │
  │  • missed chunks │                    │   (pure scoring)     │
  │  • next review   │                    └─────────────────────┘
  └──────────────────┘
```

The browser handles voice capture via the `SpeechRecognition` API (CLJS island).
The transcript is sent to the JVM backend where `core/recall.cljc` computes
similarity and identifies gaps — all in pure functions.

---

## Review Shapes (Content Views)

Each review session presents content in one of several *shapes* to test different
depths of memory. These are generated by pure functions in `core/review.cljc`:

```
  Source Text                    Shapes (progressive difficulty)
  ─────────                      ──────
  "Clojure is a dynamic,     →  1. HEADINGS ONLY
   functional language            "Clojure Overview"
   that runs on the JVM.
   It emphasises immutable    →  2. KEYWORD BLANKS
   data and first-class           "Clojure is a _______, _______
   functions..."                   language that runs on the ___."

                              →  3. SUMMARY ONLY
                                  "A functional JVM language
                                   emphasising immutability."

                              →  4. NO PROMPT (pure free recall)
                                  "What do you remember about
                                   this article?"
```

---

## Notification System (Local Network)

```
  ┌─────────┐  check schedule  ┌────────────┐  web-push  ┌─────────┐
  │  Cron   │ ───────────────► │ JVM Server │ ─────────► │  PWA    │
  │  (bb)   │  every 15 min    │ /api/notify│            │ Service │
  └─────────┘                  └────────────┘            │ Worker  │
                                                         └─────────┘
```

Since this is local-network only, the JVM server acts as its own push server.
The PWA registers for push notifications via the service worker. A Babashka
scheduled task checks `reviews.due_at` and triggers pushes for due items.

---

## Implementation Phases

### Phase 1 — Content Ingestion & Storage (Manual Entry)
> *"As a learner I want my content remembered"*
> Ingestion path: **PWA manual entry** (paste URL or raw text)

- [ ] Rename template: `bb rename seren`
- [ ] Define domain schemas in `core/schemas.cljc`
- [ ] `core/content.cljc` — normalise, chunk, extract headings
- [ ] `adapter/content_store.clj/.cljs` — PocketBase CRUD
- [ ] `app/server.clj` — `POST /api/ingest` endpoint
- [ ] PWA library page: paste URL or raw text, see ingested content
- [ ] **Tests**: TDD each core function, property-based schema tests

### Phase 2 — Spaced Repetition Scheduler
> *"As a learner I want to be reminded of content I consumed recently"*

- [ ] `core/scheduler.cljc` — SM-2 `next-review` pure function
- [ ] `adapter/review_store.clj/.cljs` — review state persistence
- [ ] `app/main.cljc` — wire ingest → initial review scheduling
- [ ] Dashboard page showing upcoming reviews
- [ ] **Tests**: property-based tests for interval monotonicity

### Phase 3 — Review Shapes
> *"As a learner I want to see content in various shapes"*

- [ ] `core/review.cljc` — `headings-only`, `keyword-blanks`, `summary`, `free-recall`
- [ ] Review session page (server-rendered + island interactivity)
- [ ] Shape selection logic based on repetition count
- [ ] **Tests**: deterministic shape generation from known inputs

### Phase 4 — Voice Free Recall & Scoring
> *"As a learner I want to perform periodic free recall"*
> Reference: `voice-pwa/modules/adapter/src/voice/adapter/audio_recorder.cljs`

- [ ] `adapter/speech.cljs` — Web Speech API wrapper (adapt from voice-pwa's `audio_recorder.cljs`)
- [ ] `core/recall.cljc` — transcript/source similarity scoring (extend voice-pwa's Dice coefficient to token-level)
- [ ] `core/recall.cljc` — missed-chunk identification
- [ ] Voice recording island with visual feedback
- [ ] Score display with highlighted gaps
- [ ] Written text fallback for browsers without SpeechRecognition
- [ ] **Tests**: similarity scoring with known transcript/source pairs

### Phase 5 — PWA, Notifications & Mobile Share Target
> *"Notify me when reviews are due"*
> Ingestion path: **Web Share Target** (mobile "Share to Seren")

- [ ] PWA manifest with `share_target` field
- [ ] Service worker with push subscription + offline support
- [ ] `adapter/notifier.clj` — web-push from JVM
- [ ] Babashka cron task to check due reviews
- [ ] Install prompt, home-screen icon
- [ ] `POST /api/ingest` route handles share target payload

### Phase 6 — Content Connections
> *"As a learner I want to see connections between content"*

- [ ] `core/content.cljc` — tag/keyword extraction, topic overlap scoring
- [ ] Graph visualisation on dashboard (D3 or simple SVG island)
- [ ] Related content suggestions during review

### Phase 7 — Browser Extension (Separate Project)
> *Convenience capture for desktop browsing*
> Ingestion path: **Browser extension** (context menu "Send to Seren")

- [ ] New repo: `seren-extension/`
- [ ] Manifest V3 config with `contextMenus` and `activeTab` permissions
- [ ] Background script: scrape `document.body.innerText`, POST to `/api/ingest`
- [ ] Toolbar icon with badge showing ingestion count
- [ ] Options page: configure Seren server URL (default `http://localhost:3000`)

---

## Educational Journey Map

Each phase is designed to teach specific Clojure concepts:

| Phase | Clojure concepts you'll learn |
|---|---|
| 1 | Namespaces, Malli schemas, pure functions, maps, destructuring |
| 2 | Higher-order functions, recursion, `loop/recur`, `#?` reader conditionals |
| 3 | Sequence operations (`map`, `filter`, `reduce`), string manipulation, regex |
| 4 | ClojureScript interop (`js/`), promises, callbacks, async patterns |
| 5 | Atoms, JVM interop (web-push lib), Babashka scripting |
| 6 | Graph data structures, `group-by`, `frequencies`, reducing functions |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Core logic | Clojure/ClojureScript (.cljc), Malli |
| Web server | Ring + Reitit (JVM) |
| Frontend | Server-rendered Hiccup + CLJS islands |
| Persistence | PocketBase (SQLite-backed, runs alongside JVM) |
| Voice capture | Web Speech API (SpeechRecognition) |
| Similarity | Token-overlap + Jaccard index (core, pure) |
| Push notifications | web-push (JVM library) |
| Styling | Open Props + Shoelace web components |
| Task runner | Babashka (bb) |

---

## Key Design Decisions

1. **No LLM dependency for scoring** — Similarity is computed via token overlap
   and Jaccard index in pure Clojure. This keeps it local, fast, and deterministic.
   An LLM-based scorer can be added later as an optional adapter.

2. **Voice via Web Speech API** — Runs in-browser, no server-side speech processing.
   Falls back to typed text input for unsupported browsers.

3. **Quality derived from similarity** — Instead of Anki's self-grading buttons,
   Seren automatically maps similarity scores to SM-2 quality values. This removes
   the subjective bias of self-assessment.

4. **Content shapes over flashcards** — Rather than generating question/answer pairs,
   we transform the source material into progressive views that test recall depth.
   This respects dual coding and retrieval practice simultaneously.

5. **Local-network first** — All data stays on your machine. PocketBase + Ring
   serve only on the LAN. No cloud, no accounts, no data leaving your network.

6. **Three ingestion paths, one endpoint** — Manual paste (Phase 1), mobile
   Web Share Target (Phase 5), and browser extension (Phase 7, separate repo)
   all converge on `POST /api/ingest`. Ingestion clients stay thin; all
   processing happens server-side in Core. The extension is deliberately a
   separate project — it's vanilla JS with no shared code, coupled only by
   the API contract.
