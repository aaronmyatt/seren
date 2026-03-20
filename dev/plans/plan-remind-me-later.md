# Phase 4.6 — "Remind Me Later" (Opt-out of Spaced Repetition)

> *"As a learner I want to save articles without committing to free-recall review — just nudge me to revisit them later."*

This phase sits after Phase 4.5 (URL Fetching). It acknowledges that not every
ingested article deserves the full SM-2 recall loop. Users ingest a lot; only a
subset is worth memorising. The rest just need a simple time-based nudge.

---

## Why This Matters

Today, `ingest-content!` **always** creates an SM-2 review due immediately.
This means every saved article enters the spaced repetition queue — even casual
reads, reference material, or "I'll get to this eventually" bookmarks. The review
queue fills up, signal drowns in noise, and the user stops reviewing altogether.

With "Remind Me Later":
- **Review** = "I want to memorise this" → SM-2 free-recall loop (existing)
- **Remind** = "Nudge me in 2 weeks" → simple time-based reminder, no recall
- **Save** = "Just archive it" → library-only, no scheduling at all

One ingestion pipeline, three outcomes — user chooses at ingest time or later.

---

## Design

### Principles

1. **Reminder ≠ Review** — Reminders are a separate, lightweight entity. They
   carry no SM-2 state (ease-factor, repetitions, scaffold). Mixing them into
   Review would pollute the schema and branch every query.
2. **Additive only** — No migration. Existing content has Reviews. Missing
   Reminder is simply "not reminded". Default ingest mode stays `:review`.
3. **Promote, don't duplicate** — When a reminder fires and the user decides
   to learn the material, dismiss the reminder and create a Review. One content
   item never has both an active reminder and an active review.
4. **Pure core** — Duration math and reminder construction are pure `.cljc`
   functions. Side effects stay in adapter.

### Ingest Mode

```
BEFORE:
  ingest-content! → always creates SM-2 review (due immediately)

AFTER:
  ingest-content! takes optional :mode
    :review  (default) → create SM-2 review (existing behaviour)
    :remind  + :remind-in {:amount 2 :unit :weeks} → create Reminder
    :save    → library only, no scheduling
```

Backward-compatible: no `:mode` field = `:review` = today's behaviour.

### Reminder Lifecycle

```
  User ingests with :remind
    → Reminder created (:status :pending, :due-at computed)
        ↓
  due-at arrives
    → Reminder surfaced on dashboard (:status :due)
        ↓
  User acts:
    ├─ Dismiss   → :status :dismissed (done)
    ├─ Snooze    → old dismissed, new Reminder created
    └─ Promote   → old dismissed, new SM-2 Review created
```

---

## Implementation

### 1. New schemas

**File**: `modules/core/src/seren/core/schemas.cljc`

```clojure
(def ReminderStatus
  [:enum :pending :due :dismissed])

(def DurationUnit
  [:enum :days :weeks :months])

(def DurationSpec
  "Human-friendly duration: {:amount 2 :unit :weeks}"
  [:map
   [:amount [:int {:min 1}]]
   [:unit DurationUnit]])

(def Reminder
  [:map
   [:id common/Id]
   [:content-id common/Id]
   [:status ReminderStatus]
   [:note {:optional true} [:maybe :string]]
   [:due-at common/Timestamp]
   [:created-at common/Timestamp]
   [:dismissed-at {:optional true} [:maybe common/Timestamp]]])

(def IngestMode
  [:enum :review :remind :save])
```

### 2. New core namespace: `reminder.cljc`

**File**: `modules/core/src/seren/core/reminder.cljc`

Pure functions — no IO, cross-platform.

| Function | Signature | Purpose |
|---|---|---|
| `duration->ms` | `DurationSpec → :int` | Convert `{:amount 2 :unit :weeks}` to millis (months ≈ 30 days) |
| `create-reminder` | `Id, Timestamp, Timestamp → Reminder` | Build reminder entity (content-id, due-at, now-ms) |
| `dismiss-reminder` | `Reminder, Timestamp → Reminder` | Set `:status :dismissed`, `:dismissed-at now` |
| `snooze-reminder` | `Reminder, Timestamp, Timestamp → {:dismissed Reminder :new Reminder}` | Dismiss old + create new at snoozed time |
| `reminder-due?` | `Reminder, Timestamp → boolean` | Predicate: non-dismissed and due-at ≤ now |

### 3. New adapter: `reminder_store.clj` / `.cljs`

**Files**:
- `modules/adapter/src/seren/adapter/reminder_store.clj`
- `modules/adapter/src/seren/adapter/reminder_store.cljs`

Follows `review_store.clj` pattern exactly — EDN files in a directory, `store-dir`
as first argument.

| Function | Purpose |
|---|---|
| `save-reminder!` | Write `{id}.edn` |
| `load-reminder` | Read by ID |
| `list-reminders` | All reminders sorted by due-at |
| `list-due-reminders` | Non-dismissed where due-at ≤ now-ms |
| `find-reminders-by-content` | All reminders for a content-id |
| `delete-reminder!` | Remove file |

### 4. Update app wiring

**File**: `modules/app/src/seren/app/main.cljc`

**4a.** Add `:reminder-dir` to config.

**4b.** Update `ingest-content!` — after saving content, branch on `:mode`:

```clojure
(case (or mode :review)
  :review  ;; existing: create initial-review
  :remind  ;; new: create reminder using remind-in duration
  :save    ;; new: no scheduling, return content only
  )
```

**4c.** New public functions:

| Function | Purpose |
|---|---|
| `list-due-reminders` | Wrap adapter, return due reminders |
| `dismiss-reminder!` | Load → core/dismiss → save |
| `snooze-reminder!` | Load → core/snooze → save both |
| `promote-reminder!` | Dismiss reminder → `start-review!` with content-id |
| `set-reminder!` | Create reminder for existing library content |

### 5. API routes

**File**: `modules/app/src/seren/app/server.clj`

| Method | Path | Body | Purpose |
|---|---|---|---|
| `GET` | `/api/reminders/due` | — | Due reminders |
| `GET` | `/api/reminders` | — | All reminders |
| `POST` | `/api/reminders` | `{:content-id "..." :remind-in {:amount 2 :unit :weeks}}` | Create reminder for existing content |
| `POST` | `/api/reminders/:id/dismiss` | — | Dismiss |
| `POST` | `/api/reminders/:id/snooze` | `{:remind-in {:amount 1 :unit :weeks}}` | Snooze |
| `POST` | `/api/reminders/:id/promote` | — | Promote to SM-2 review |

Existing `POST /api/ingest` passes `:mode` and `:remind-in` through to
`ingest-content!` — no handler change needed beyond ensuring the fields
aren't stripped.

Update `app-config` to include `:reminder-dir`.

### 6. UI updates

**Dashboard** (`pages/dashboard.clj`): Add "Reminders" section alongside
"Due for Review". Each reminder card shows content title + due date + actions
(Dismiss / Snooze / Start Review).

**Library** (`pages/library.clj` + `islands/library.cljs`): Ingest form gains
a mode selector — "Review now" (default) / "Remind me later" (shows duration
picker) / "Just save". Existing content cards gain a "Remind me" action.

---

## Task Checklist

- [ ] `core/schemas.cljc` — `ReminderStatus`, `DurationUnit`, `DurationSpec`, `Reminder`, `IngestMode`
- [ ] `core/reminder.cljc` — pure functions: `duration->ms`, `create-reminder`, `dismiss-reminder`, `snooze-reminder`, `reminder-due?`
- [ ] **Tests**: `core/reminder_test.cljc` — duration math, create/dismiss/snooze/promote, due? predicate
- [ ] `adapter/reminder_store.clj` — EDN file-backed store (follows `review_store.clj` pattern)
- [ ] `adapter/reminder_store.cljs` — atom-backed CLJS counterpart
- [ ] **Tests**: `adapter/reminder_store_test.clj` — save/load/list/due/delete with temp dir fixtures
- [ ] `app/main.cljc` — update `ingest-content!` to branch on `:mode`; add reminder app functions
- [ ] **Tests**: `app/main_test.cljc` — ingest with `:mode :remind`, `:mode :save`, default
- [ ] `app/server.clj` — reminder API routes + `app-config` update
- [ ] **Tests**: reminder API endpoint tests (JSON payloads)
- [ ] Dashboard page — "Reminders" section
- [ ] Library page — mode selector on ingest form, "Remind me" action on content cards

---

## Clojure Concepts Introduced

| Concept | Where |
|---|---|
| `case` dispatch on keyword | `app/main.cljc` — ingest mode branching |
| Multi-value returns (map of results) | `core/reminder.cljc` — `snooze-reminder` returns `{:dismissed :new}` |
| Keyword-based enums with Malli | `core/schemas.cljc` — `[:enum :review :remind :save]` |

---

## Backward Compatibility

- **No migration**: existing content has Reviews, no Reminders. That's fine.
- **Default mode is `:review`**: omitting `:mode` = today's behaviour exactly.
- **Existing API consumers** (bookmarklet, Chrome extension) send no `:mode` → auto-review as before.
- **New `:reminder-dir`** created on first write, like `review-dir`.
