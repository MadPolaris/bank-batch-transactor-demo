# Value Objects

Shared immutable domain types — no identity, no lifecycle. Referenced by
`id` from aggregates, slices, policies, and the pipeline.

| File | Type |
|---|---|
| `classification.vo.yaml` | Result tri-classification: Success / Failure / Suspicious |
| `error-code-mapping.vo.yaml` | Business-configurable code → classification mapping |
| `next-step.vo.yaml` | The four routing actions |
| `rebatch-policy.vo.yaml` | Retry budget + decision table (incl. the demo's two policies) |
| `ack-result.vo.yaml` | External-system acknowledgement outcome |
| `poll-result.vo.yaml` | Response-poll outcome |
| `batch-file.vo.yaml` | The three file artifacts flowing through the chain |
| `batch-key.vo.yaml` | Batch key format + delimiter invariant (A4) |
| `demo-customer.vo.yaml` | Demo customer + item encoding |
