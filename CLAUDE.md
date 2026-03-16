# FCIS Project Conventions

This is a Clojure project following the **Functional Core, Imperative Shell** (FCIS) pattern. It is structured as a monorepo with three layers, designed for productive human-LLM collaboration. Core logic is cross-platform (`.cljc`) — it compiles for both JVM Clojure and ClojureScript.

## Architecture

```
Core (pure, .cljc)  →  Adapter (side effects, .clj/.cljs)  →  App (wiring, .cljc)
```

- **Core** (`modules/core/`): Pure functions only. No IO, no atoms, no side effects. `.cljc` files — runs on JVM and JS.
- **Adapter** (`modules/adapter/`): Side-effectful code. Persistence is backed by [PocketBase](https://pocketbase.io/) — all calls are proxied through the JVM backend. Platform-specific: `.clj` for JVM, `.cljs` for browser. Same API on both platforms.
- **App** (`modules/app/`): Orchestration layer. `.cljc` for shared wiring, `.cljs` for browser entry point.
- **Shared** (`shared/`): Cross-cutting utilities. CLI runner (`.clj`, JVM-only) and common schemas (`.cljc`).

## Key Rules

1. **Every public function** must have a Malli schema via `(m/=> fn-name [:=> ...])` after its definition
2. **Every public function** must have a docstring
3. **Core functions must be pure** — no IO, no atoms, no `def` mutation, no side effects
4. **Adapter functions** take dependencies as arguments (e.g., `store-dir`) — no globals
5. **Prefer maps** over positional arguments for functions with 3+ parameters
6. **Every source file** ends with a `(comment ...)` block containing 2-3 REPL-evaluable examples
7. **Validations** return result maps `{:valid? bool :reason str}` — not exceptions
8. **Core and shared schemas use `.cljc`** — avoid JVM interop; use `#?` reader conditionals when platform-specific code is unavoidable

## Agent Workflow

1. **No unsolicited documentation** — Do not generate README files, changelogs, doc files, or verbose markdown unless explicitly requested. Prefer concise inline documentation over separate files.
2. **Educational comments with links** — Code comments should teach, not just label. Include links to relevant official documentation, RFCs, or explanatory resources whenever possible. Example:
   ```clojure
   ;; Malli function schemas register the spec for runtime validation and CLI discovery.
   ;; See: https://github.com/metosin/malli#function-schemas
   (m/=> validate-email [:=> [:cat :string] ValidationResult])
   ```
3. **TDD: Red/Green workflow** — When adding or modifying functionality:
   - Write a failing test first (red)
   - Make the minimal change to pass the test (green)
   - Refactor only if needed
   - Run tests after each step (`bb test:core`, `bb test:cljs`, etc.)
4. **Proactive suggestions** — When you notice opportunities to improve code quality, architecture, developer experience, or project conventions, suggest them. Flag technical debt, missing tests, inconsistent patterns, or potential simplifications. Suggestions should be clearly marked as optional and not acted on without approval.
5. **Human-makes-the-edit** — For small, localized changes (type hints, renaming, fixing a single line, adding an import, tweaking a value), guide the human to make the edit themselves rather than applying it with agent tools. Describe *what* to change and *where*, but let them do it. This builds muscle memory, keeps the human engaged with their own codebase, and saves tokens. Reserve agent edits for larger, multi-file, or repetitive changes where manual editing would be tedious or error-prone.

## Cross-Platform Rules

- **Core files must be `.cljc`** — no `java.*` imports without a `#?` reader conditional
- **Adapter has paired implementations**: `user_store.clj` (JVM) and `user_store.cljs` (CLJS) with the same public API
- **Use `#?(:clj ... :cljs ...)` sparingly** — only for platform-specific operations like timestamps or I/O
- **Malli schemas work on both platforms** — `m/=>` annotations compile for JVM and JS

## Adding a New Function

1. Define the function with a docstring
2. Add `(m/=> fn-name [:=> [:cat <input-schemas>] <output-schema>])` after it
3. Add a `(comment ...)` example at the end of the file
4. Write tests in the corresponding `test/` directory
5. The function automatically appears in the CLI: `bb core:cli list`
6. If adding to Adapter: create both `.clj` and `.cljs` implementations

## Testing

- Core: unit tests + property-based tests using `test.check` and Malli generators
- Adapter: integration tests — JVM (temp directory fixtures), CLJS (atom reset fixtures)
- App: smoke/end-to-end tests
- Run JVM tests: `bb test:all`, `bb test:core`, `bb test:adapter`, `bb test:app`
- Run CLJS tests: `bb test:cljs`

## CLI Discovery

Each layer has a CLI for discovering and invoking functions (JVM, reads `.cljc` files):

```bash
bb core:cli list                                    # List all Core functions
bb core:cli describe seren.core.user/validate-email  # Show schema and docstring
bb core:cli run seren.core.user/validate-email '"test@example.com"'  # Invoke with EDN args
bb adapter:cli list                                 # List Adapter functions (JVM version)
bb app:cli list                                     # List App functions
```

## Schemas

- **Shared schemas** (used across modules): `shared/src/seren/schemas/common.cljc`
- **Domain schemas** (module-specific): `modules/<module>/src/seren/<module>/schemas.cljc`
- Schemas are plain EDN data — vectors and maps. Reference them in function schemas.

## File Placement

| What | Where | Extension |
|------|-------|-----------|
| Pure business logic | `modules/core/src/seren/core/` | `.cljc` |
| Domain schemas | `modules/core/src/seren/core/schemas.cljc` | `.cljc` |
| JVM side-effectful code | `modules/adapter/src/seren/adapter/` | `.clj` |
| CLJS side-effectful code | `modules/adapter/src/seren/adapter/` | `.cljs` |
| Application wiring | `modules/app/src/seren/app/` | `.cljc` |
| Browser entry point | `modules/app/src/seren/app/browser.cljs` | `.cljs` |
| Cross-cutting schemas | `shared/src/seren/schemas/common.cljc` | `.cljc` |
| CLI runner (JVM-only) | `shared/src/seren/cli/runner.clj` | `.clj` |
| Tests | `modules/<module>/test/seren/<module>/` | `.cljc` or `.clj`/`.cljs` |

## Starting a New Project from This Template

After cloning or creating a repo from this template, rename the `seren` namespace prefix to your project name:

```bash
bb rename myproject   # Renames all namespaces, directories, and config references
bb test:all           # Verify JVM tests pass
bb test:cljs          # Verify CLJS tests pass
```

This replaces `seren` everywhere — namespace declarations, directory paths, `deps.edn`, `shadow-cljs.edn`, `package.json`, and this file. The script validates that the name is a valid Clojure namespace segment (lowercase, hyphens allowed). Run it once immediately after cloning.

## PocketBase

[PocketBase](https://pocketbase.io/) is the default persistence layer. It runs as a single binary alongside the JVM backend — all browser requests are proxied through the server.

```bash
bb pb:install     # Download the latest PocketBase binary for this platform
bb pb:start       # Start PocketBase (admin UI: http://127.0.0.1:8090/_/)
bb pb:stop        # Stop PocketBase
```

- Binary and data live in `.pocketbase/` (git-ignored)
- `bb deps` includes PocketBase download automatically
- The download script (`scripts/pocketbase.bb`) detects OS/arch and fetches the correct release from GitHub

## Bootstrap (LLM Sandboxes)

If `clojure` or `bb` (babashka) are not available on the system, run:

```bash
npm install       # postinstall hook auto-installs clojure + bb locally
export PATH="$PWD/.clojure-tools/bin:$PWD/.bb/bin:$PATH"
```

Or run the scripts directly:
- `./scripts/ensure-clojure.sh` — installs Clojure CLI to `.clojure-tools/` (requires Java)
- `./scripts/ensure-bb.sh` — installs babashka to `.bb/bin/`

Both directories are git-ignored. Java (JDK 8+) is a prerequisite — the Clojure script will error with install instructions if missing.

## Common Tasks

```bash
bb tasks          # Show all available tasks
bb test:all       # Run all JVM tests
bb test:core      # Run Core tests only (JVM)
bb test:cljs      # Run ClojureScript tests (Node.js)
bb core:cli list  # Discover Core functions
bb nrepl          # Start nREPL with all modules
bb cljs:watch     # Start shadow-cljs dev server (http://localhost:8000)
bb cljs:release   # Build optimized JS bundle
bb pb:install     # Download PocketBase binary
bb pb:start       # Start PocketBase server
bb pb:stop        # Stop PocketBase server
bb clean          # Remove build artifacts
bb deps           # Download all dependencies (JVM + npm + PocketBase)
bb rename <name>  # Rename namespace prefix for new project
```
