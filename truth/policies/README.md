# Policies

Reactive domain rules: when trigger events fire, which slices get invoked,
decided by an ordered rule table (first match wins).

- **`routing-decision`** — failure → next step (retry / abandon / manual).
- **`reconfirm-rule`** — suspicious → verify against the authoritative source → final.
- **`business-closure`** — what happens when a batch run completes: reconfirm,
  route, settle, and chain recharge → purchase.
- The exactly-once accounting law lives as invariants on `customer-order`
  (see `aggregates/customer-order/aggregate.yaml`); the stats view reconciles against it.
