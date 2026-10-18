# Truth Tree — bank-batch-transactor-demo

This directory is the **domain truth** of the bank batch transactor demo, reverse-engineered
from the source code (scenario A: read-only extraction, no code was generated from it).

## What this demo is

Batch **recharge** (充值) and **purchase** (申购) transactions against an external bank /
fund-company system. Each batch of items runs through a **six-stage resumable chain**
(file-gen → upload → wait-ack → poll → parse → classify): every completed stage
records its progress, so an interruption resumes from the breakpoint instead of
re-running completed stages. Results are tri-classified (success / failure /
suspicious); failures are routed (retry / abandon / manual), suspicious items are
reconfirmed, and every customer order lands in exactly one terminal state.

## How to navigate (top-down, no tooling needed)

| Directory | What's in it |
|---|---|
| `workshop.yaml` | Root manifest: schema version, workshop name, session order |
| `aggregates/` | The two consistency boundaries: `batch-chain-run`, `customer-order` |
| `pipelines/` | The six-stage chain definition (stage order, slots, failure codes) |
| `value-objects/` | Shared immutable domain types (classification, next-step, policies…) |
| `policies/` | Reactive domain rules (routing, reconfirm, business closure, ledger invariant) |
| `read-models/` | What the web UI serves (stats, exception pool, event feed) |
| `scenarios/` | The scale-demo script (100k customers, deterministic outcomes) |
| `inbox/` | The 13 workshop products (event flow … read models), one dir per product |

Each directory has its own `README.md`. Every atom file starts with
`kind` — read it plus `name` and you know what it is. (Format migrated to
truthsrc v0.4 on 2026-10-18 — see `MIGRATION-NOTES.md`.)

## Conventions

- **Stable IDs, never copied text.** Cross-references use `id` (e.g. `aggregate: batch-chain-run`,
  `event: stage-completed`). If a name changes, the id stays.
- **One file = one typed record.** The `kind` comes from the truthsrc v0.4 vocabulary
  (see `zhongdj/metarch-truthsrc` `SCHEMA.md`); the validator
  enforces required fields per kind.
- **Chinese alongside English** where the concept is domain-heavy (recharge/purchase
  vocabulary), following the repo's `README-zh.md` precedent.
- **No machine smell.** Fields that exist only for byte-identity or compiler bookkeeping
  (ordering keys, sequence numbers, codegen hints) do not belong here — the compiler owns those.

## Machine gate

The toolchain lives in `zhongdj/metarch-truthsrc` (not in this repo):

```bash
node bin/truthsrc.js validate --tree <path-to>/truth
node bin/truthsrc.js roundtrip --tree <path-to>/truth
```

`validate` is the structural gate — 0 hard errors required (notices annotate
modeling quality). `roundtrip` proves `compile ∘ decompose` is byte-identical:
the tree is the only edit surface; `canonical.json` is a pure projection.
Humans decide domain truth; the tool only reports structural breakage.

## What this tree is NOT

- Not a second copy of the code — it captures *domain facts* (stage order, failure codes,
  routing tables, terminal states), not implementation.
- Not generated from a workshop — it was extracted from working code, so the vocabulary
  is pipeline/slice/policy rather than event-storming sticky notes.
- See `EXTRACTION-NOTES.md` for what was deliberately left out and where the code was
  ambiguous (those need a human arbiter, not the extractor).
