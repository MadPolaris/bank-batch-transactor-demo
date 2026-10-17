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
| `aggregates/batch-chain-run` — run lifecycle, journal events, persistence contract | `core/.../component/ChainExecutionActor.scala`, `web/.../bank/WebChain.scala` |
| `aggregates/customer-order` — terminal ledger, exactly-once invariant | `web/.../bank/BankBatchDemoService.scala` (terminalStates, setTerminal) |
| slices `settle-*` / `abandon` / `escalate` / `park` / `replay` | `BankBatchDemoService.businessClosure`, `replayException(s)` |
| slices `start-batch-run` / `simulate-crash` | `ChainExecutionActor.startRun`, `WebChain.SimulateCrash`, `crashRandom` |
| `value-objects/` — classification, next-step, policies, ack/poll, files, customer | `ResultClassifier.scala`, `ReBatchRouter.scala`, `SubBatchPipeline.scala`, `BankBatchDemoService.scala` |
| `policies/` — routing, reconfirm, business closure | `businessClosure`, `PolicyBasedReBatchRouter`, `reconfirmOf` |
| `read-models/` — stats, exception pool, event feed | `BankBatchController.scala`, `routes`, `chainStatsJson`, `BankBatchJson` |
| `scenarios/scale-demo` — 100k script, outcome table | `BankBatchDemoService` (outcomeOf, reconfirmOf, updateAccountGlitchy) |
| `scenarios/console-demo` — sbt run tour | `core/.../demo/BankBatchDemo.scala` |
| Snapshot schema (referenced, not duplicated) | `core/src/main/protobuf/bank_chain.proto`, `BankStateCodec.scala` |

## Deliberately left out

- **Framework glue.** Akka actor wiring, Play DI/routes mechanics, JSON frame-builder
  internals, the deterministic-hash implementation, simulated-pipeline sleep
  durations. The *domain facts* these produce (frame types, outcome table) are in
  the tree; the *mechanisms* are not.
- **Unwired legacy components.** `SubBatchProcessor` (the pre-Monarch for-comprehension),
  `AreaScheduler`/`WindowedAreaScheduler`, and the `ReconfirmHandler` trait are present
  in `core/.../component/` but nothing in the demo calls them — the demo runs on
  `BankChain` + `PersistentMonarch`, and reconfirm is implemented inline
  (`reconfirmOf`). They are real library code, but they are not *this demo's* truth.
- **Test specs.** `BankChainSpec` / `ChainExecutionActorSpec` verify behavior; they don't
  add domain facts beyond what's extracted.
- **Compiler-owned bookkeeping.** No seq/order/keyOrder fields anywhere — per the
  metarch review, those belong to the compiler, not the tree.

## Arbitrated 2026-10-18 — A1..A4 resolved, tree + code updated

The user arbitrated all four on 2026-10-18 ("按照完整一致的方式修，要有说服力").
The standard: the system's own invariants must hold — money/ledger/feed agree,
one entity type, ledger durable, key format enforced. Code and tree were changed
together; the tree below records the decided semantics.

### A1. [ARBITRATED] Replay is a legal transition except_pool → credited/paid
Decision: replay success moves the terminal state. `TerminalLedger.transitionTerminal`
does an atomic compare-and-set (current must be exactly `except_pool`), applied
BEFORE the money moves; on mismatch the replay is refused, money untouched, pool
entry restored. Code: `BankBatchDemoService.replayException`, `TerminalLedger`.
Tree: slice `replay-exception`, aggregate `customer-order` lifecycle + invariants.
Tests: `TerminalLedgerSpec` (8), `ReplayExceptionSpec` (4, real service).

### A2. [ARBITRATED] One canonical sharding registration
Decision: exactly one entity type, `"m25-chain-executor"`
(`ChainExecutionActor.EntityTypeName`, single source of truth in core).
`WebChain.initSharding` registers the same name — it is a behavior flavor
(adds `SimulateCrash`) on the identical journal namespace, not a second type.
`ChainExecutionActor.initSharding` remains the bare flavor for non-web hosts;
registering both in one ActorSystem is rejected by Akka (the guard).
Tree: aggregate `batch-chain-run` identity.sharding.

### A3. [ARBITRATED] Ledger is a read model, rebuilt from the journal
Decision: no second write model. On service (re)start, `LedgerRecovery` reads the
latest run's entity journals (StageDone protobuf snapshots carry full
classifications) and replays them through `BankDecisions.decideTerminal` — the
same pure mapping the live `businessClosure` uses, by construction (shared code,
not duplicated logic). Restores terminal states, the seen-set, counters, and the
exception pool. Mutually exclusive with `startRun` (stale recovery results are
discarded). Journals from before the A2 migration (old type-key prefix) are not
recovered. Code: `LedgerRecovery`, `BankDecisions`, `BankBatchDemoService.initSharding`.
Tree: aggregate `customer-order` persistence; this note.
Tests: `BankDecisionsSpec` (12), `LedgerRecoverySpec` (6).

### A4. [ARBITRATED] Batch-key invariant enforced at creation (+ broken decode fixed)
Decision: `BatchKey` owns encode/decode as inverse operations; chain names and
runIds containing '-' are rejected where the key is created (fail fast).
Found while fixing: the old `parseBatchKey` decoded as if there were no runId
segment while `jobKey` included one — it threw NumberFormatException on real keys,
and `chainOfBatchId` returned the runId instead of the chain. Both fixed by the
single-owner change. Tree: new value-object `batch-key`.
Tests: `BatchKeySpec` (6).

## Ambiguous spots — [NEEDS CALL]

### A5. `onCompleted` carries no snapshot (API workaround)
The new `RunObserver.onCompleted(runId)` doesn't receive the final snapshot, so the
demo keeps a `latestSnapshots` cache written by `onStageComplete`. Implementation
detail — not domain truth, not in the tree. Noted here so nobody "fixes" the tree
for it.

## Validation

`python3 truth/tools/validate-tree.py` → **CLEAN, 0 hard errors**
(28 atoms: 2 aggregates, 8 slices, 1 pipeline, 9 value-objects, 3 policies,
3 read-models, 2 scenarios; 11 events in the pool).

What the gate does NOT check (human territory): whether the Chinese descriptions
are accurate, whether A1–A4 are resolved correctly, and whether the tree is
*complete enough* — that last one is a review judgment, see README.
