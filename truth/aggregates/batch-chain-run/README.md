# Batch Chain Run

One execution of the six-stage batch chain for a single batch — the unit that
gets journaled, snapshotted, crashed, and resumed.

- `aggregate.yaml` — identity, state slots (the `BankChainState` slots), lifecycle,
  invariants, and the persistence contract (per-stage protobuf snapshots, cursor
  resume, generation guard).
- `slices/` — the two commands that act on a run:
  - `start-batch-run` — begin execution (duplicate starts are rejected).
  - `simulate-crash` — demo-only: kill the entity, keep the journal, resume on restart.
