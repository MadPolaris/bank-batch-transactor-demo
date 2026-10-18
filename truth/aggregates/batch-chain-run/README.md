# Batch Chain Run

One execution of the six-stage batch chain for a single batch — the unit that
records progress per stage, gets interrupted, and resumes from the breakpoint.

- `aggregate.yaml` — identity, run state, lifecycle, invariants, and the
  progress-recording contract (per-stage progress records, breakpoint resume).
- `slices/` — the two commands that act on a run:
  - `start-batch-run` — begin execution (duplicate starts are rejected).
  - `simulate-crash` — demo-only: stop the process, keep the progress records, resume on restart.
