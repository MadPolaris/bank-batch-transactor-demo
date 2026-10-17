# Read Models

What the web UI serves — each file says where it's served, what it derives
from, and how each field is computed.

- **`chain-stats`** — `GET /api/bank-batch/state`: the reconciliation dashboard
  (terminal ledger per chain + the `balanced` check).
- **`exception-pool`** — `GET /api/bank-batch/exceptions`: parked orders + replay.
- **`event-feed`** — `WS /ws/bank-batch/events`: the live frame stream the page renders.
