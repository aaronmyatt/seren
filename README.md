# Seren

> **Seren** (Welsh: *star*) — a local-network learning agent that captures what
> you read online and guides you back through it with voice-driven free recall.

No flashcards. No manual notes. Just speak what you remember, and Seren tells
you what you missed.

## What It Does

You browse the web — documentation, blog posts, video transcripts. Seren
captures that content and schedules spaced retrieval practice sessions. When a
review is due, your phone buzzes. You open the PWA, see a prompt, and **speak
aloud** everything you remember. Seren transcribes your voice, compares it to
the source material, scores your recall, and highlights the gaps.

Built on the [Six Strategies for Effective Learning](https://www.learningscientists.org/blog/2016/8/18-1):
retrieval practice, spaced practice, elaboration, interleaving, concrete
examples, and dual coding.

## How It Works

```
  You browse the web          Seren captures content
        │                            │
        ▼                            ▼
  ┌───────────┐  POST /api/ingest  ┌──────────────┐
  │  Browser  │ ─────────────────► │  JVM Backend  │
  │ Extension │                    │  (Ring+Reitit) │
  └───────────┘                    └──────┬───────┘
                                          │
                    ┌─────────────────────┼────────────────────┐
                    │                     │                    │
                    ▼                     ▼                    ▼
             ┌───────────┐      ┌──────────────┐     ┌──────────────┐
             │  Content  │      │  Scheduler   │     │  PocketBase  │
             │  Chunking │      │  (SM-2)      │     │  (Storage)   │
             │  (Core)   │      │  (Core)      │     └──────────────┘
             └───────────┘      └──────────────┘

  Review is due → push notification → you open the PWA:

  ┌─────────────┐  tap "record"  ┌──────────────┐  transcript  ┌───────────┐
  │  PWA Review │ ─────────────► │ Web Speech   │ ───────────► │  Scoring  │
  │  Page       │                │ API (browser)│              │  (Core)   │
  └─────────────┘                └──────────────┘              └─────┬─────┘
                                                                     │
                                                                     ▼
                                                          ┌─────────────────┐
                                                          │  Your Score     │
                                                          │  85% similarity │
                                                          │  Missed: X, Y   │
                                                          │  Next review:   │
                                                          │  3 days         │
                                                          └─────────────────┘
```

## Architecture

Built on the **Functional Core, Imperative Shell** pattern in Clojure/ClojureScript.
See [CLAUDE.md](./CLAUDE.md) for full conventions.

```
Core (pure .cljc)  →  Adapter (side effects .clj/.cljs)  →  App (wiring .cljc)
```

The Core module contains all learning logic as pure functions — SM-2 scheduling,
similarity scoring, content chunking, review shape generation. These compile to
both JVM and JavaScript, and every function has a Malli schema. The Adapter
handles PocketBase persistence, push notifications, and the Web Speech API. The
App layer wires everything together behind a Ring HTTP server with CLJS islands
for interactivity.

## Prerequisites

- Java 11+
- [Babashka](https://github.com/babashka/babashka#installation)
- [Clojure CLI](https://clojure.org/guides/install_clojure) 1.12+
- [Node.js](https://nodejs.org/) 18+

## Quick Start

```bash
bb deps                   # Download JVM + npm + PocketBase dependencies
bb pb:start               # Start PocketBase (admin: http://127.0.0.1:8090/_/)
bb dev                    # Start PocketBase + shadow-cljs + Ring server
```

Then open `http://localhost:3000` on any device on your local network.

## Development

```bash
bb test:all               # Run all JVM tests
bb test:cljs              # Run ClojureScript tests
bb core:cli list          # Discover Core functions
bb nrepl                  # Start REPL with all modules
```

## Project Plan

See [plan.md](./plan.md) for the full implementation plan, architecture diagrams,
data model, and phased roadmap.

## Learning Science References

- [The Learning Scientists — Six Strategies](https://www.learningscientists.org/blog/2016/8/18-1)
- [Retrieval Practice Guide](https://www.learningscientists.org/downloadable-materials)
- [SM-2 Algorithm](https://super-memory.com/archive/help16/smalg.htm)
- [Web Speech API (MDN)](https://developer.mozilla.org/en-US/docs/Web/API/Web_Speech_API)