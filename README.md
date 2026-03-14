# Clojure FCIS Template

A Clojure project template following the **Functional Core, Imperative Shell** (FCIS) pattern, designed for productive human-LLM collaboration.

## Architecture

The project is organized into three layers:

- **Core** (`.cljc`) — Pure functions: business logic, validation, data transformation. No side effects. Cross-platform — runs on JVM and in the browser.
- **Adapter** (`.clj` / `.cljs`) — Side-effectful code backed by [PocketBase](https://pocketbase.io/). All browser requests are proxied through the JVM backend. Platform-specific implementations, same API.
- **App** (`.cljc` / `.cljs`) — Orchestration: wires Core and Adapter together. Entry points for JVM CLI, HTTP servers, and browser frontends.

Each layer has a CLI interface auto-generated from [Malli](https://github.com/metosin/malli) schemas, enabling both humans and LLMs to discover and invoke functions without reading source code. The same Malli schemas work on both JVM and ClojureScript.

## Prerequisites

- [Clojure CLI](https://clojure.org/guides/install_clojure) (1.12+)
- [Babashka](https://github.com/babashka/babashka#installation)
- Java 11+
- [Node.js](https://nodejs.org/) 18+ (for ClojureScript builds)
- `unzip` (for PocketBase binary extraction — pre-installed on most systems)

## Quick Start

```bash
# Download dependencies (JVM + npm + PocketBase)
bb deps

# Start PocketBase (admin UI at http://127.0.0.1:8090/_/)
bb pb:start

# Run all JVM tests
bb test:all

# Run ClojureScript tests (via Node.js)
bb test:cljs

# Discover what functions are available
bb core:cli list
bb adapter:cli list
bb app:cli list

# Describe a specific function
bb core:cli describe fcis.core.user/validate-email

# Invoke a function from the command line
bb core:cli run fcis.core.user/validate-email '"alice@example.com"'

# Start a REPL with all modules loaded
bb nrepl

# Start ClojureScript dev server (browser demo at http://localhost:8000)
bb cljs:watch
```

## Project Structure

```
modules/
  core/       Pure functions, schemas (.cljc — JVM + JS)
  adapter/    Side-effectful code (.clj for JVM, .cljs for browser)
  app/        Application wiring (.cljc), browser entry point (.cljs)
shared/       CLI runner (.clj), cross-cutting schemas (.cljc)
dev/          REPL helpers (JVM)
public/       Browser demo (index.html, compiled JS)
```

## Available Tasks

Run `bb tasks` to see all available commands:

| Task | Description |
|------|-------------|
| `bb test:all` | Run all JVM tests |
| `bb test:core` | Run Core tests only |
| `bb test:adapter` | Run Adapter tests only |
| `bb test:app` | Run App tests only |
| `bb test:cljs` | Run ClojureScript tests (Node.js) |
| `bb core:cli <cmd>` | CLI for Core functions |
| `bb adapter:cli <cmd>` | CLI for Adapter functions |
| `bb app:cli <cmd>` | CLI for App functions |
| `bb nrepl` | Start nREPL |
| `bb cljs:watch` | Start shadow-cljs dev server with hot reload |
| `bb cljs:release` | Build optimized JS bundle |
| `bb pb:install` | Download PocketBase binary |
| `bb pb:start` | Start PocketBase server |
| `bb pb:stop` | Stop PocketBase server |
| `bb deps` | Download all dependencies (JVM + npm + PocketBase) |
| `bb clean` | Remove build artifacts |

## How It Works

Every public function is annotated with a [Malli](https://github.com/metosin/malli) schema:

```clojure
(defn validate-email
  "Checks if an email address is valid."
  [email]
  ...)

(m/=> validate-email [:=> [:cat :string] [:map [:valid? :boolean] [:reason [:maybe :string]]]])
```

The CLI runner introspects these schemas at runtime to provide discovery (`list`), documentation (`describe`), and invocation (`run`) without any manual CLI maintenance.

## Cross-Platform (JVM + ClojureScript)

The FCIS layering maps naturally to the cross-platform boundary:

| Layer | JVM | ClojureScript |
|-------|-----|---------------|
| **Core** | `.cljc` — same code | `.cljc` — same code |
| **Adapter** | `.clj` — PocketBase HTTP client | `.cljs` — proxied through JVM backend |
| **App** | `.cljc` — same orchestration | `.cljs` — browser entry point |

Core is pure functions — no platform-specific code except one reader conditional for timestamps. The Adapter layer has separate implementations for each platform (same API, same Malli schemas). The App orchestration layer doesn't care which Adapter it's using.

### Browser Demo

```bash
npm install             # Install shadow-cljs
bb cljs:watch           # Start dev server with hot reload
# Open http://localhost:8000 — check the browser console
```

The browser demo (`modules/app/src/fcis/app/browser.cljs`) runs the same Core validation and registration logic that works on the JVM, using an in-memory Adapter instead of file I/O.

### ClojureScript Tests

```bash
bb test:cljs            # Run CLJS tests via Node.js (shadow-cljs :node-test)
```

Tests cover the in-memory Adapter and App wiring on the JS platform.

### Adding a New Adapter

When adding a new side-effectful operation:

1. Define the function in `modules/adapter/src/fcis/adapter/your_adapter.clj` (JVM)
2. Create a matching `your_adapter.cljs` with the same public API (CLJS)
3. Apply the same Malli schemas in both files
4. The App layer (`.cljc`) requires the adapter by namespace — the compiler picks the right file per platform

## PocketBase

[PocketBase](https://pocketbase.io/) is the default persistence layer — a single Go binary with SQLite, auth, and a built-in admin UI.

```bash
bb pb:install     # Download the latest binary (auto-detects OS/arch)
bb pb:start       # Start server at http://127.0.0.1:8090
bb pb:stop        # Stop server
```

- `bb deps` downloads PocketBase automatically alongside JVM and npm dependencies
- Binary and data directory live in `.pocketbase/` (git-ignored)
- Admin UI available at `http://127.0.0.1:8090/_/` — use it to create collections, manage data, and configure auth
- All browser requests are proxied through the JVM backend; the CLJS adapter does not talk to PocketBase directly

## Using This Template

1. Clone or fork this repository
2. Rename the `fcis` namespace prefix to your project name (find-and-replace across all files)
3. Replace the example user domain with your actual business logic
4. Keep the three-layer structure and schema conventions

## LLM Collaboration

This template is designed for human-LLM pair programming. See `CLAUDE.md` for conventions that both parties follow, including agent workflow rules (TDD, educational comments, documentation policy). Key principles:

- Small, pure functions with schemas create a clear contract
- The CLI provides a neutral discovery interface for both parties
- Rich `(comment ...)` blocks serve as inline documentation
- Separate test files catch regressions automatically
