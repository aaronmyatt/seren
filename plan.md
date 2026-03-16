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
| **Dual Coding** | After recall, gaps are shown alongside the original — visual comparison reinforces learning |

---

## FCIS Module Map

```
modules/
├── core/src/seren/core/           ← Pure .cljc (JVM + JS)
│   ├── content.cljc               ← Content normalisation, chunking, summarisation
│   ├── recall.cljc                ← Similarity scoring, gap detection (Phase 4)
│   ├── scheduler.cljc             ← SM-2 interval calculations ✅
│   ├── review.cljc                ← Scaffold generators (headings, summary, keyword-blanks)
│   └── schemas.cljc               ← All domain schemas
│
├── adapter/src/seren/adapter/     ← Side effects
│   ├── content_store.clj/.cljs    ← CRUD for ingested content (PocketBase)
│   ├── review_store.clj/.cljs     ← Review history persistence
│   ├── notifier.clj               ← PWA push notifications (web-push, JVM only)
│   ├── audio.cljs                 ← MediaRecorder wrapper for voice capture (browser only)
│   └── whisper.clj                ← whisper.cpp HTTP client for local transcription (JVM only)
│
└── app/src/seren/app/             ← Wiring
    ├── main.cljc                 ← Orchestration (ingest → schedule → review → score)
    ├── server.clj                ← Ring routes: /api/ingest, /api/reviews, /api/score
    ├── pages/                    ← Server-rendered Hiccup
    │   ├── library.clj           ← Content library view
    │   ├── review.clj            ← Review session page
    │   └── dashboard.clj         ← Score history, upcoming reviews
    └── islands/                  ← CLJS interactivity
        ├── review.cljs           ← Review session: voice recording, transcription, scoring
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
  ┌─────────┐   tap    ┌────────────┐  WebM/Opus  ┌──────────────────┐
  │  PWA    │ ──────►  │  Record    │ ──────────► │ POST /api/       │
  │  Review │  "start" │  (island)  │    blob      │ transcribe       │
  │  Page   │          │  MediaRec. │              │ (JVM proxy)      │
  └─────────┘          └────────────┘              └────────┬─────────┘
                                                            │
                                                  audio → whisper.cpp
                                                  (local, port 8178)
                                                            │
                                                      transcript
                                                            │
                                                            ▼
  ┌──────────────────┐  POST /api/:id/score   ┌─────────────────────┐
  │  Score Display   │ ◄──────────────────── │   JVM Backend        │
  │  • similarity %  │                        │   core/recall.cljc   │
  │  • missed chunks │                        │   (pure scoring)     │
  │  • next review   │                        └─────────────────────┘
  └──────────────────┘
```

The browser captures audio via the MediaRecorder API (CLJS island). The audio
blob is POSTed to the JVM backend which proxies it to whisper.cpp for local
transcription — no internet required. The transcript is then scored by
`core/recall.cljc` which computes similarity and identifies gaps — all in
pure functions.

---

## Review Flow: Free Recall + Scaffolding

Every review session begins with **free recall** — the user speaks or types
everything they remember, with no cues from the source material. This is the
core learning act; the recall attempt itself strengthens memory.

If the user struggles (low similarity score, or long interval since last review),
the system offers **scaffolding** — progressively stronger hints to help them
re-engage with the material without giving the whole thing away:

```
  ALWAYS: Free Recall (user speaks/types what they remember)
  ─────────────────────────────────────────────────────────

  If struggling, scaffold with progressively stronger hints:

  Level 0: NO SCAFFOLD (default)
           "What do you remember about this article?"
           └── Pure retrieval practice — maximum learning benefit

  Level 1: HEADINGS ONLY (lightest hint)
           "Clojure Overview → Immutability → Functions"
           └── Structural cues jog memory without giving away content

  Level 2: SUMMARY (medium hint)
           "A functional JVM language emphasising immutability."
           └── High-level gist lets user fill in the details

  Level 3: KEYWORD BLANKS (strongest hint)
           "Clojure is a _______, _______ language that runs on the ___."
           └── Almost the full text — used when content is very difficult
```

**Scaffold selection** is determined by the review's SM-2 state:
- First review, or quality ≥ 3 last time → Level 0 (no scaffold)
- Quality 2 last time → Level 1 (headings)
- Quality 1 last time → Level 2 (summary)
- Quality 0 last time, or interval > 30 days → Level 3 (keyword blanks)

The user can also manually request a hint during a session, which bumps the
scaffold level up by one (but doesn't change the SM-2 scoring).

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

### Phase 1 — Content Ingestion & Storage (Manual Entry) ✅
> *"As a learner I want my content remembered"*
> Ingestion path: **PWA manual entry** (paste URL or raw text)

- [x] Rename template: `bb rename seren`
- [x] Define domain schemas in `core/schemas.cljc` (Content, Chunk, Heading, ContentInput)
- [x] `core/content.cljc` — normalise, chunk, extract headings, summarise, extract tags
- [x] `adapter/content_store.clj/.cljs` — file-based (JVM) + atom-based (CLJS) CRUD
- [x] `app/server.clj` — `POST /api/ingest` + `GET /api/content` EDN endpoints
- [x] PWA library page: paste text, optional title/URL, see ingested content cards
- [x] Dashboard page: content stats (item count, chunk count)
- [x] **Tests**: TDD core content functions (14 test cases, schema validation)
- [x] **Tooling**: clj-kondo linting (`bb lint`) + pre-commit hook

### Phase 2 — Spaced Repetition Scheduler ✅
> *"As a learner I want to be reminded of content I consumed recently"*

- [x] `core/scheduler.cljc` — SM-2 `next-review`, `similarity->quality`, `initial-review`, `apply-review`
- [x] `core/schemas.cljc` — Review, SchedulerInput/Output, Quality, EaseFactor, ReviewStatus, ReviewShape
- [x] `adapter/review_store.clj/.cljs` — file-based (JVM) + atom-based (CLJS) review persistence
- [x] `app/main.cljc` — wire ingest → initial review scheduling, `complete-review!`, `list-due-reviews`
- [x] `app/server.clj` — `GET /api/reviews`, `GET /api/reviews/due`, `POST /api/reviews/:id/complete`
- [x] Dashboard page showing upcoming reviews with shape badges and due status
- [x] **Tests**: 10 test cases — SM-2 interval calculations, ease factor floor, schema conformance, interval monotonicity, similarity→quality boundaries

### Phase 3 — Review Session (Free Recall + Scaffolding) ✅
> *"As a learner I want to practice recalling content with help when I'm stuck"*

Free recall is the default for every review. Scaffolds (headings, summary,
keyword-blanks) are hints offered when the user has struggled previously or
the content interval is long. The user always attempts recall first.

- [x] `core/review.cljc` — scaffold generators: `headings-scaffold`, `summary-scaffold`, `keyword-blanks-scaffold`
- [x] `core/review.cljc` — `select-scaffold-level` pure function (SM-2 state → scaffold level 0-3)
- [x] Review session page (`pages/review.clj`) — server-rendered with free-recall textarea + scaffold reveal
- [x] Review island (`islands/review.cljs`) — handles text input, scaffold toggle, submit to `/api/reviews/:id/complete`
- [x] Update `ReviewShape` schema → `ScaffoldLevel` (`:none`, `:headings`, `:summary`, `:keyword-blanks`)
- [x] Route: `GET /review/:id` — starts a review session for a specific review
- [x] `server.clj` — `wrap-exceptions` middleware for error visibility
- [x] **Tests**: 7 test groups — scaffold generation from known inputs, scaffold selection from SM-2 state

### Phase 4 — Voice Input & Similarity Scoring ✅
> *"As a learner I want to speak my recall and see what I missed"*
> Reference: `voice-pwa/modules/adapter/src/voice/adapter/audio_recorder.cljs`

Voice is an input method for free recall — it replaces typing, not the
review flow. Similarity scoring compares the transcript (voice or typed)
against source material to derive SM-2 quality automatically.

- [x] `adapter/audio.cljs` — MediaRecorder wrapper for voice capture (adapted from voice-pwa's `audio_recorder.cljs`)
- [x] `adapter/whisper.clj` — whisper.cpp HTTP client for local transcription (JVM, multipart POST to port 8178)
- [x] `scripts/whisper.bb` — Download whisper.cpp server binary + base.en model (like PocketBase pattern)
- [x] `bb.edn` — `whisper:install`, `whisper:start`, `whisper:stop` tasks
- [x] `core/recall.cljc` — transcript/source similarity scoring (Jaccard index at token level, stop-word filtering)
- [x] `core/recall.cljc` — missed-chunk identification (which chunks weren't recalled?)
- [x] Voice recording button with visual feedback (pulsing red during recording, status line shows duration)
- [x] Score display: similarity %, missed chunks with left-border highlight, next review date
- [x] Written text fallback for browsers without MediaRecorder (voice button hidden, textarea always available)
- [x] Wire: `POST /api/transcribe` → whisper.cpp proxy → transcript
- [x] Wire: `POST /api/reviews/:id/score` → `score-recall` → `similarity->quality` → SM-2 → complete + schedule next
- [x] Manual quality buttons retained as "Rate manually" fallback
- [x] `core/schemas.cljc` — `RecallScore` schema
- [x] **Tests**: tokenization (with stop-word stripping), similarity with known pairs, missed chunk detection with threshold, full scoring pipeline

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
| 3 | Sequence operations (`map`, `filter`, `reduce`), string manipulation, regex, Reitit path params |
| 4 | ClojureScript interop (`js/`), promises, callbacks, async patterns, MediaRecorder API, JVM HTTP proxying |
| 5 | Atoms, JVM interop (web-push lib), Babashka scripting, service workers |
| 6 | Graph data structures, `group-by`, `frequencies`, reducing functions |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Core logic | Clojure/ClojureScript (.cljc), Malli |
| Web server | Ring + Reitit (JVM) |
| Frontend | Server-rendered Hiccup + CLJS islands |
| Persistence | PocketBase (SQLite-backed, runs alongside JVM) |
| Voice capture | MediaRecorder API (browser) → whisper.cpp (local transcription) |
| Similarity | Token-overlap + Jaccard index (core, pure) |
| Push notifications | web-push (JVM library) |
| Styling | Open Props + Shoelace web components |
| Task runner | Babashka (bb) |

---

## Key Design Decisions

1. **No LLM dependency for scoring** — Similarity is computed via token overlap
   and Jaccard index in pure Clojure. This keeps it local, fast, and deterministic.
   An LLM-based scorer can be added later as an optional adapter.

2. **Voice via whisper.cpp** — Audio captured in-browser via MediaRecorder, transcribed
   locally by whisper.cpp (C/C++ port of OpenAI Whisper). No internet required, no
   trusted certificate needed. Falls back to typed text input if MediaRecorder unavailable.

3. **Quality derived from similarity** — Instead of Anki's self-grading buttons,
   Seren automatically maps similarity scores to SM-2 quality values. This removes
   the subjective bias of self-assessment.

4. **Free recall first, scaffolding when stuck** — Every review starts with
   unassisted free recall (the act that builds memory). Scaffolds (headings,
   summary, keyword-blanks) are progressively stronger hints offered only
   when the user has struggled previously. This maximises retrieval practice
   while preventing frustration on difficult content.

5. **Local-network first** — All data stays on your machine. PocketBase + Ring
   serve only on the LAN. No cloud, no accounts, no data leaving your network.

6. **Three ingestion paths, one endpoint** — Manual paste (Phase 1), mobile
   Web Share Target (Phase 5), and browser extension (Phase 7, separate repo)
   all converge on `POST /api/ingest`. Ingestion clients stay thin; all
   processing happens server-side in Core. The extension is deliberately a
   separate project — it's vanilla JS with no shared code, coupled only by
   the API contract.
