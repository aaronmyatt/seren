# Ping Pong TDD Mode

You are now in **Ping Pong TDD mode**. This is a collaborative test-driven development workflow where you and the human alternate between writing tests and implementing code.

## The Loop

Each time this mode is activated, follow these steps in order:

### Step 1: Assess the current state
Run the tests (`bb test:all` and/or `bb test:cljs` as appropriate). Determine:
- Are there **failing tests**? → Go to Step 2 (Implement)
- Are all tests **passing**? → Go to Step 3 (Write a new test)

### Step 2: Implement (Green)
- Read the failing test(s) carefully. Understand what behavior they expect.
- Write the **minimal code** to make the failing test(s) pass. No more, no less.
- Run the tests again to confirm they pass.
- Commit the implementation with a message like: `Green: <short description of what was implemented>`
- Proceed to Step 3.

### Step 3: Write a failing test (Red)
- Based on the feature context below, write **one** new failing test that describes the next small increment of behavior.
- The test should be focused, specific, and test exactly one thing.
- Run the tests to confirm the new test **fails** (red).
- Commit the failing test with a message like: `Red: <short description of what the test expects>`
- Proceed to Step 4.

### Step 4: Hand back
**Stop working.** Do not implement the code for the test you just wrote. Instead:
- Explain what the new failing test expects in plain language.
- Mention the test file and test name.
- Say something like: "Your turn — make it green!"

Then **wait for the human** to respond. Do not proceed until they do.

## Rules
- **Never implement your own test.** Always hand back after writing a failing test.
- **Minimal changes only.** Don't refactor, don't add extras, don't "improve" unrelated code.
- **One test at a time.** Write exactly one new failing test per turn, not a batch.
- **Follow project conventions.** Malli schemas, docstrings, `.cljc` where appropriate, comment blocks with examples — per the project's CLAUDE.md.
- **Small commits.** Commit the green implementation and the red test separately.

## Feature Context

$ARGUMENTS
