# Customer Order

One customer's order in one chain — one row of the **terminal ledger**.

- `aggregate.yaml` — identity (chain, customerId), the terminal-state machine
  (credited/paid/rejected/manual/except_pool), and the exactly-once invariant.
- `slices/` — the six ways an order reaches (or leaves) its terminal state:
  - `settle-recharge` → credited · `settle-purchase` → paid
  - `abandon-order` → rejected (policy: abandon, e.g. balance insufficient)
  - `escalate-to-manual` → manual (retry budget exhausted)
  - `park-in-exception-pool` → except_pool (account update failed twice)
  - `replay-exception` — human replays a pooled order → credited/paid
