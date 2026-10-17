# bank-batch-transactor-demo

A **standalone runnable demo** for the [MadPolaris](https://github.com/MadPolaris) engine
family: financial batch transaction processing (BankChain — sub-batch pipelines,
re-batch routing, result classification, reconfirm handling).

Built on [`monarch-core`](https://github.com/MadPolaris/monarch-core) (resumable
stage-queue engine) + [`monarch-persistence`](https://github.com/MadPolaris/monarch-persistence)
(Akka Persistence journaling, cursor-based resume) + [`common-core`](https://github.com/MadPolaris/common-core).

Extracted from [MadPolaris/playAkkaCQRS](https://github.com/MadPolaris/playAkkaCQRS)
(`dag-engine-core/` module). [中文版](README-zh.md).

## Modules

| Module | Description |
|--------|-------------|
| `core/` | Engine: six-stage BankChain, `PersistentMonarch` facade, protobuf state snapshots, tests |
| `web/` | Play 2.8 demo page: batch dashboard, WebSocket event stream, crash-injection button |

## Run it

Console demo (six mechanical stages against a simulated bank):

```bash
sbt "core / run"
```

This launches `net.imadz.m25.demo.BankBatchDemo`:

- **Scenario 1** — happy path: 3 payment items, mixed Success / Failure / Suspicious results
- **Scenario 2** — failure path: the bank goes silent, `wait-ack` fails with a classified
  `ACK_TIMEOUT` `StageFailedException` (the same failure vocabulary the production chain journals)

Web demo (100,000 customers · 2,000/batch · 50 batches · concurrency 6):

```bash
sbt "web / run"
```

Then open http://localhost:9000/bank-batch — recharge chain (partner bank) → purchase
chain (fund company), with live progress, exception pool, manual replay, and a crash
button that kills a running batch mid-flight to demonstrate breakpoint resume.

### Self-contained distribution

```bash
sbt stage
./target/universal/stage/bin/bank-batch-transactor-demo
```

No sbt needed on the target machine — just a Java 11+ runtime.

## How recovery works

Each batch runs as one Akka Persistence entity (`PersistentMonarch`). After every stage,
a `StageDone` event journals the cursor **plus a protobuf snapshot** of the full
`BankChainState` (`core/src/main/protobuf/bank_chain.proto` — field numbers are the
cross-version compatibility contract, no Java serialization).

On restart the entity replays the journal and resumes from the last completed cursor —
completed stages never re-run. The web demo's crash button stops the entity outright;
the next message triggers recovery from the journal.

## Tests

```bash
sbt "core / test"
```

9 tests, including a real crash→restart breakpoint-resume test (stages a/b run once,
stage c re-runs once after restart) that validates the protobuf snapshot round-trip.

## Note

This is a demo application, not a library: it is never published to Maven Central
(`publish / skip := true`).

License: Apache-2.0.
