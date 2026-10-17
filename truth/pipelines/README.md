# Pipelines

The stage queues this demo executes.

- **`batch-chain`** — the six-stage external-system chain. The array order in the
  file IS the execution order. Each stage declares: what it reads, what it writes,
  the failure codes it can throw (as classified `StageFailedException`), the
  metadata it emits on completion, and its precondition.

The chain is engine-agnostic in the tree: `monarch-core` runs it in-process
(`BankBatchDemo`), `monarch-persistence` journals it per stage
(`ChainExecutionActor` / `WebChain`). Both execute the same six stages.
