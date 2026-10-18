# Extraction Notes — bank-batch-transactor-demo truth tree

How this tree was built, what was left out on purpose, and where the code was
ambiguous. **The user — not the extractor — arbitrates domain truth.** Anything
marked **[NEEDS CALL]** below is a question, not a conclusion.

Source: `MadPolaris/bank-batch-transactor-demo @ 864ecd3` (2026-10-17).
Method: scenario A, read-only reverse engineering. No code was generated from this tree.

## What was extracted (and from where)

| Tree area | Source files |
|---|---|
| `pipelines/batch-chain` — six stages, slots, failure codes, metadata | `core/.../component/BankChain.scala` (BankStage, runStage, metadataOf) |
| `aggregates/batch-chain-run` — run lifecycle, per-stage progress records, resume-from-breakpoint | `core/.../component/ChainExecutionActor.scala`, `web/.../bank/WebChain.scala` |
| `aggregates/customer-order` — terminal ledger, exactly-once invariant | `web/.../bank/BankBatchDemoService.scala` (terminalStates, setTerminal) |
| slices `settle-*` / `abandon` / `escalate` / `park` / `replay` | `BankBatchDemoService.businessClosure`, `replayException(s)` |
| slices `start-batch-run` / `simulate-crash` | run start, crash-simulation command |
| `value-objects/` — classification, next-step, policies, ack/poll, files, customer | `ResultClassifier.scala`, `ReBatchRouter.scala`, `SubBatchPipeline.scala`, `BankBatchDemoService.scala` |
| `policies/` — routing, reconfirm, business closure | `businessClosure`, `PolicyBasedReBatchRouter`, `reconfirmOf` |
| `read-models/` — stats, exception pool, event feed | `BankBatchController.scala`, `routes`, `chainStatsJson`, `BankBatchJson` |
| `scenarios/scale-demo` — 100k script, outcome table | `BankBatchDemoService` (outcomeOf, reconfirmOf, updateAccountGlitchy) |
| `scenarios/console-demo` — sbt run tour | `core/.../demo/BankBatchDemo.scala` |
| Progress-record schema (referenced, not duplicated) | progress-record field layout |

## Deliberately left out

- **Framework glue.** Actor wiring, web-framework DI/routes mechanics, JSON frame-builder
  internals, the deterministic-hash implementation, simulated-pipeline sleep
  durations. The *domain facts* these produce (frame types, outcome table) are in
  the tree; the *mechanisms* are not.
- **Unwired legacy components.** The old for-comprehension batch processor,
  the area schedulers, and the reconfirm-handler trait are present
  in `core/.../component/` but nothing in the demo calls them — the demo runs the
  six-stage chain with progress records, and reconfirm is implemented inline.
  They are real library code, but they are not *this demo's* truth.
- **Test specs.** `BankChainSpec` / `ChainExecutionActorSpec` verify behavior; they don't
  add domain facts beyond what's extracted.
- **Compiler-owned bookkeeping.** No seq/order/keyOrder fields anywhere — per the
  metarch review, those belong to the compiler, not the tree.

## Arbitrated 2026-10-18 — A1..A4 resolved, tree + code updated

The user arbitrated all four on 2026-10-18 ("按照完整一致的方式修，要有说服力").
The standard: the system's own invariants must hold — money/ledger/feed agree,
one run per batch, ledger durable across restarts, key format enforced. Code and
tree were changed together; the tree below records the decided semantics in
problem-space language (what the business means, not how the code implements it).

### A1. [ARBITRATED] Replay success is a legal transition except_pool → credited/paid
Decision: a successful human replay moves the terminal state. The ledger and the
money move together or not at all — the transition is applied before the money
moves; if the order is no longer in except_pool the replay is refused, the money
is untouched, and the pool entry is restored. Money, ledger, and event feed
always agree.
Tree: slice `replay-exception`, aggregate `customer-order` lifecycle + invariants.
Tests: 8 ledger-transition tests, 4 replay tests (incl. a real-service test
proving terminal == credited/paid after replay).

### A2. [ARBITRATED] One run per batch, addressed the same way everywhere
Decision: a batch has exactly one run. The web UI and the engine both address
the same run — there is never a second, parallel run for the same batch, and a
repeat start for an active or finished run is refused. The web's crash-simulation
capability is a flavor of the same run, not a separate one.
Tree: aggregate `batch-chain-run` identity (single_run).

### A3. [ARBITRATED] The ledger is a read model, rebuilt from progress records
Decision: no second write model. On service (re)start, the service replays the
latest runs' progress records (which carry the full classifications) through the
terminal-decision rules — the same rules the live business closure uses, by
construction (one shared rule set, not two copies) — and restores terminal
states, the seen-set, and the exception pool. A service crash no longer loses
the ledger; the progress records are the single source of truth. Rebuild and a
fresh run start are mutually exclusive (stale rebuild results are discarded).
Tree: aggregate `customer-order` (ledger_durability); this note.
Tests: 12 terminal-decision tests, 6 ledger-rebuild tests.

### A4. [ARBITRATED] Batch-key invariant enforced at creation (+ broken decode fixed)
Decision: the batch key owns its format in one place — encoding and decoding
are inverse operations that cannot drift apart. Chain names and runIds
containing '-' are rejected where the key is created (fail fast).
Found while fixing: the old decoder assumed a key shape the encoder never
produced — it crashed on real keys, and the chain-name lookup returned the
runId instead of the chain. Both fixed by the single-owner change.
Tree: new value-object `batch-key`.
Tests: 6 batch-key tests.

## Ambiguous spots — [NEEDS CALL]

### A5. Run-completion carries no final state (implementation workaround)
The run-completion notification doesn't carry the final run state, so the demo
keeps a small cache of the latest per-stage progress. Implementation detail —
not domain truth, not in the tree. Noted here so nobody "fixes" the tree for it.

## Validation

`python3 truth/tools/validate-tree.py` → **CLEAN, 0 hard errors**
(28 atoms: 2 aggregates, 8 slices, 1 pipeline, 9 value-objects, 3 policies,
3 read-models, 2 scenarios; 11 events in the pool).

What the gate does NOT check (human territory): whether the Chinese descriptions
are accurate, whether A1–A4 are resolved correctly, and whether the tree is
*complete enough* — that last one is a review judgment, see README.
