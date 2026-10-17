# bank-batch-transactor-demo

A **standalone runnable demo** for [`monarch-core`](https://github.com/MadPolaris/monarch-core):
financial batch transaction processing (BankChain — sub-batch pipelines, re-batch
routing, result classification, reconfirm handling).

Built on [`monarch-core`](https://github.com/MadPolaris/monarch-core) (resumable stage-queue
engine) + [`common-core`](https://github.com/MadPolaris/common-core).

Extracted from [MadPolaris/playAkkaCQRS](https://github.com/MadPolaris/playAkkaCQRS)
(`dag-engine-core/` module).

## Run it

```bash
sbt run
```

This launches `net.imadz.m25.demo.BankBatchDemo`, which drives the six mechanical
stages (`file-gen → upload → wait-ack → poll → parse → classify`) against a simulated
external bank system:

- **Scenario 1** — happy path: 3 payment items, mixed Success / Failure / Suspicious results
- **Scenario 2** — failure path: the bank goes silent, `wait-ack` fails with a classified
  `ACK_TIMEOUT` `StageFailedException` (the same failure vocabulary the production chain journals)

### Self-contained distribution

```bash
sbt stage
./target/universal/stage/bin/bank-batch-transactor-demo
```

No sbt needed on the target machine — just a Java 11+ runtime.

## Tests

```bash
sbt test
```

## Note

This is a demo application, not a library: it is never published to Maven Central
(`publish / skip := true`).

License: Apache-2.0.
