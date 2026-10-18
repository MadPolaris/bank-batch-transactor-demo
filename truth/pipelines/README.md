# Pipelines

The stage queues this demo executes.

- **`batch-chain`** — the six-stage external-system chain. The array order in the
  file IS the execution order. Each stage declares: what it reads, what it writes,
  the failure codes it can produce (classified), the output info it records on
  completion, and its precondition.

The chain describes the business process — build the file, send it, wait for the
receipt confirmation, pull the result, decode it, classify every item — not how
any particular runtime executes or persists it.
