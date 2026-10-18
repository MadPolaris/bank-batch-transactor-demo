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
| `tree.yaml` | Root manifest: name, version, provenance, full index |
| `types.yaml` | The **type vocabulary** — the 7 record kinds every file uses |
| `aggregates/` | The two consistency boundaries: `batch-chain-run`, `customer-order` |
| `pipelines/` | The six-stage chain definition (stage order, slots, failure codes) |
| `value-objects/` | Shared immutable domain types (classification, next-step, policies…) |
| `policies/` | Reactive domain rules (routing, reconfirm, business closure, ledger invariant) |
| `read-models/` | What the web UI serves (stats, exception pool, event feed) |
| `scenarios/` | The scale-demo script (100k customers, deterministic outcomes) |
| `tools/` | `validate-tree.py` — the machine gate (see below) |

Each directory has its own `README.md`. Every atom file starts with
`kind` / `id` / `name` / `description` — read those four lines and you know what it is.

## Conventions

- **Stable IDs, never copied text.** Cross-references use `id` (e.g. `aggregate: batch-chain-run`,
  `event: stage-completed`). If a name changes, the id stays.
- **One file = one typed record.** The `kind` comes from `types.yaml`; the validator
  enforces required fields per kind.
- **Chinese alongside English** where the concept is domain-heavy (recharge/purchase
  vocabulary), following the repo's `README-zh.md` precedent.
- **No machine smell.** Fields that exist only for byte-identity or compiler bookkeeping
  (ordering keys, sequence numbers, codegen hints) do not belong here — the compiler owns those.

## Machine gate

```bash
python3 truth/tools/validate-tree.py truth/
```

Checks: every file parses, kinds/ids valid, ids unique, all cross-references resolve,
every aggregate has ≥1 slice, every policy trigger resolves to a produced event.
Humans decide domain truth; the tool only reports structural breakage.

## What this tree is NOT

- Not a second copy of the code — it captures *domain facts* (stage order, failure codes,
  routing tables, terminal states), not implementation.
- Not generated from a workshop — it was extracted from working code, so the vocabulary
  is pipeline/slice/policy rather than event-storming sticky notes.
- See `EXTRACTION-NOTES.md` for what was deliberately left out and where the code was
  ambiguous (those need a human arbiter, not the extractor).
