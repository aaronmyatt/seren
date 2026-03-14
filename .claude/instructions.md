# Claude Code Instructions for Seren

## Project Overview

Seren is a voice-driven spaced repetition learning agent built as a Clojure FCIS (Functional Core, Imperative Shell) monorepo. Read `CLAUDE.md` in the project root for architecture, conventions, and agent workflow rules (TDD, comments, documentation policy). Read `plan.md` for the implementation roadmap.

## When Modifying Code

### Adding a function to Core
1. Write the function in the appropriate namespace under `modules/core/src/seren/core/`
2. Add a docstring explaining what it does
3. Add `(m/=> fn-name [:=> [:cat <inputs>] <output>])` immediately after the `defn`
4. Use schemas from `seren.core.schemas` or `seren.schemas.common` where possible
5. Add examples to the `(comment ...)` block at the end of the file
6. Write tests in `modules/core/test/seren/core/` — include at least one property-based test for non-trivial functions
7. Run `bb test:core` to verify

### Adding a function to Adapter
1. Write the function under `modules/adapter/src/seren/adapter/`
2. Pass all dependencies (file paths, connections, etc.) as arguments — no global state
3. Follow the same schema/docstring/comment conventions as Core
4. Write integration tests with the `temp-dir-fixture` pattern (see existing tests)
5. Run `bb test:adapter` to verify

### Adding a function to App
1. Write the function under `modules/app/src/seren/app/`
2. Follow the same conventions
3. Run `bb test:app` to verify

## Schema Conventions

- Use `common/Id` for string identifiers
- Use `common/Timestamp` for unix timestamps
- Use `common/Email` for email strings
- Use `common/ValidationResult` for single validation results
- Use `common/ValidationResults` for aggregated results with error lists
- Define new domain types in the module's `schemas.cljc`

## Error Handling

- Pure functions return result maps (e.g., `{:valid? false :reason "..."}`) — never throw exceptions for expected cases
- Adapter functions may throw for infrastructure failures (file not found, network error)
- App functions should handle adapter exceptions and return structured results

## Testing Patterns

- Core: `(deftest fn-name-test ...)` with `(testing "scenario" ...)` blocks
- Property tests: use `tc/quick-check` with Malli generators or custom `gen/fmap` generators
- Adapter: use `temp-dir-fixture` with `use-fixtures :each` for file I/O tests
- Always verify outputs against Malli schemas in property tests: `(m/validate Schema result)`

## Verification Commands

```bash
bb test:all       # Must pass before any PR
bb test:core      # Quick check for Core changes
bb core:cli list  # Verify new functions are discoverable
```
