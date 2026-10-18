# Aggregates

Two consistency boundaries, each owning its slices:

- **`batch-chain-run/`** — one execution of the six-stage chain for one batch.
  Identity = runId. Owns the run lifecycle (start → per-stage progress records →
  completed/failed, interruption → resume from breakpoint). See `pipelines/batch-chain.pipeline.yaml`
  for what the six stages actually do.
- **`customer-order/`** — one customer's order in one chain, i.e. one row of the
  terminal ledger. Identity = (chain, customerId). Owns the exactly-once terminal
  state machine (credited/paid/rejected/manual/except_pool).

Slices live in `<aggregate>/slices/` and reference their aggregate by id.
