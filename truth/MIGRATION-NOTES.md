# Migration notes — pilot dialect → truthsrc v0.4 (2026-10-18)

Pure format migration. **No domain content changed**: every description,
rule, field, guard, step, and arbitration note from the pilot tree is
preserved (mechanically verified: all 350 free-text strings >25 chars from
the pilot files appear verbatim in the new tree, modulo YAML folding
whitespace).

Gates (from a checkout of `zhongdj/metarch-truthsrc`):

```bash
node bin/truthsrc.js validate --tree <path-to>/truth    # ✅ 0 hard · 21 notice (all notices explained below)
node bin/truthsrc.js roundtrip --tree <path-to>/truth   # ✅ roundtrip identity — 97 atoms, canonical byte-stable
```

## What changed, per kind

- **Root**: `tree.yaml` deleted, replaced by `workshop.yaml`
  (`schemaVersion` + `name: bank-batch-transactor-demo` + the 7-stage
  `sessionOrder`). Note: `schemaVersion` is `truthsrc/v1`, not `v0.4` —
  the toolchain's canonical schema stays `v1` by design (v0.4 is the
  *tree-format* version; "v0.4 additive extension — schemaVersion stays
  truthsrc/v1", per SCHEMA.md and the golden example tree).
- **Header discipline (roundtrip-forced)**: the toolchain's `decompose`
  regenerates every atom byte-for-byte from `canonical.json`, which only
  carries headers `kind`/`name` (+`type` for notes/policies,
  `command`/`aggregate` for slices, `id`/`description` for
  pipelines/scenarios). Any extra header (`id`, `name_zh`, `description`,
  `description_zh`, …) would be dropped by `decompose` and break the
  roundtrip gate — so all of them moved **into the `payload` JSON**
  (verbatim). This is why atoms look "header-minimal": it is a fidelity
  requirement, not a content cut.
- **Aggregates** (`aggregates/*/aggregate.yaml`): pilot
  `identity:` → `payload.identifier` (see judgment calls);
  `state_slots:` → `payload.stateFields` (`type:` → `dataType:`, RF3);
  `lifecycle:` from/via/to rows → `payload.stateGates`
  (`{via: {fromStates, toState}}`) + `payload.statusTransitions`
  (`{via: to}`); `invariants:` → `payload.aggregateRuleRefs`
  (`{ruleId, desc, desc_zh}`); `ledger_durability:` →
  `payload.ledgerDurability`; `progress_recording:` →
  `payload.progressRecording`; batch-key format/invariant/single-run notes
  → `payload.identityDetail`. `order:` block added (`order.commands` ≡
  slice set, `order.events` ≡ event pool — V5/V6, both directions).
  `statusEnum` derived from lifecycle terminal states. `seq` omitted (v0.4:
  compiler-derived).
- **Slices** (`aggregates/*/slices/*.slice.yaml`): headers now exactly
  `kind`/`aggregate`/`command` (`aggregate` is the aggregate **name**,
  e.g. `Customer Order` — V4). Pilot `id:` ≡ `command:` (kebab). Slice
  description/name_zh moved into `payload` (`payload.name` ≡ header
  `command`, V4). `parameters:` → `payload.parameters` (`type:` →
  `dataType:`); `guards:` (`- description:` rows) →
  `payload.guards` (plain strings); `produces:` unchanged;
  event `payload:` rows → `events:` block with `payloads:` and mandatory
  `name` ≡ event key (V4).
- **Policies** (`policies/*.policy.yaml`): pilot `invokes: - slice: X` →
  `- command: X` + `aggregate:` (the aggregate **name**). Structured
  `triggers:`/`invokes:` blocks are tool-authoritative; `payload.links`
  ≡ their projection and `payload.info.triggers`/`invokes` deep-equal the
  blocks (V8, both directions). Pilot `rules:` → `payload.rules`
  (`{when, then, note}`). Trigger qualifier notes
  (`- event: run-completed` + `note: …` / `run-completed (with …)`) →
  `payload.triggerNote`. The three `inbox/10-policies/` notes
  (BatchBusinessClosure, SuspiciousReconfirmation, FailureRoutingDecision)
  are `type: policy` notes, so `decompose` normalizes them to
  `kind: policy` atoms in `policies/` — they now live there as
  `policy-rf-…-38/39/40.policy.yaml` (roundtrip requirement; the
  `inbox/10-policies/` dir is empty). **Dedupe (2026-10-18)**: the
  three `inbox/10-policies/` notes (BatchBusinessClosure,
  SuspiciousReconfirmation, FailureRoutingDecision) were condensed
  restatements of the three original policies, so `policy-rf-…-38/39/40`
  were true duplicates and have been removed; the originals
  (`…-35/36/37`, richer: carry `id` + detailed `rules`) are canonical.
  No other atom referenced the removed names, so no reference fixes were
  needed. The six policies had shared one
  `origin.seq` counter for `(refining, reconstructed)`: inbox refining
  notes 0–34, then policies 35–40 (V2 uniqueness is per stage+board
  across inbox + policies); after the dedupe the counter runs 0–37.
- **Value objects** (`value-objects/*.vo.yaml`): headers `kind`/`name`
  only; everything else into `payload` (`id`, `name_zh`, descriptions,
  `fields` with `dataType:`, `variants`, `algorithm`, `decision`,
  `inline_types`, `invariants`, `demo_policies`, `item_encoding`,
  `chain_item`). V10 requires `payload.fields` to be an array — the five
  sum-type VOs (ack-result, batch-file-artifacts, next-step, poll-result,
  result-classification) carry their fields per variant, so they get
  `"fields": []` at top level. Two files renamed to the deterministic
  slug of their name: `batch-file.vo.yaml` →
  `batch-file-artifacts.vo.yaml`, `classification.vo.yaml` →
  `result-classification.vo.yaml`.
- **Read models** (`read-models/*.rm.yaml`): headers `kind`/`name`;
  `payload` carries `id`, `name_zh`, descriptions, `served_by`,
  `sources` (as aggregate **names**), `sourceAggregate`, `visible_to`
  (kept verbatim), and `fields` with `dataType` + `source` (V11 — see
  judgment calls). Renamed to name slugs: `chain-stats.rm.yaml` →
  `chain-statistics.rm.yaml`, `event-feed.rm.yaml` →
  `live-event-feed.rm.yaml` (`exception-pool.rm.yaml` unchanged).
- **Pipeline** (`pipelines/batch-chain.pipeline.yaml`): pilot `stages:`
  → **`steps:`** (v0.4 vocabulary — "stage" is overloaded). Step rows
  keep `id`/`name`/`name_zh`/`description`/`reads`/`writes` plus
  `precondition`, `failure_codes`, `output_info`. `failure_semantics`
  stays a scalar header (rides the canonical; PL3-clean).
- **Scenarios** (`scenarios/*.scenario.yaml`): scalar headers
  (`id`, `name`, `name_zh`, `description`, `description_zh`) + one opaque
  `script:` block holding `scale`/`chains`/`outcome_rules`/`observability`/
  `chaining`/`crash_demo` (SC2: no other object blocks allowed).
- **Inbox notes** (65 notes across 12 product dirs): headers exactly
  `kind: note`/`type`/`name` + `origin: {stage, board: reconstructed,
  seq}`. `origin.stage` uses the full stage name mapped from the product
  dir (01→eventStorming, 02→commandStorming, 03→aggregateDiscovery,
  05→contextMapping, 06/07/08/09/12/13→refining, 11→storyMapping;
  04 skipped, 10-policies promoted to `policies/`). `origin.seq` is
  0..n-1 dense per `(stage, board)` in product-dir order — required for
  the roundtrip gate (`decompose` re-emits `seq` as the canonical index).
  Filenames follow the toolchain convention
  `<type>-<stagecode>-<slug(name)>-<seq>.yaml`. All former headers
  (`name_zh`, `description`, `sequence`, `payload_fields`, `triggers`,
  `produces`, `parameters`, `guards`, `can_issue`, `can_view`,
  `cardinality`, `meaning`, `fields`, `links`, `preconditions`, `steps`,
  `extensions`, `role`, `goal`, `benefit`, `served_by`, `sources`,
  `visible_to`) moved into `payload` verbatim. Pilot note types outside
  the toolchain vocabulary were remapped (V1): `valueObject`→`entity`,
  `aggregateState`→`entity`, `aggregateRelation`→`relation`,
  `messageFields`→`field`, `lineage`→`relation`, `useCase`→`story`.
  Event notes carry `payload.info.timeline: "batch-chain"` (the
  pipeline id) and `payload.info.sequence` = the pilot `sequence`
  (flow position, problem-space); command notes link
  `event:<NoteName>`; role notes keep `can_issue`/`can_view` verbatim
  and link `command:<NoteName>` / `readModel:<NoteName>`.

## Dropped (and why)

- `truth/tree.yaml` — replaced by `workshop.yaml` (v0.4 root shape).
- `truth/types.yaml` — the kind vocabulary now lives in the toolchain
  (`zhongdj/metarch-truthsrc` SCHEMA.md / `lib/shared.mjs`); a per-tree
  copy would drift.
- `truth/tools/validate-tree.py` — the forked pilot validator; replaced
  by the real toolchain (`node bin/truthsrc.js validate|roundtrip`).
- YAML folded scalars (`description: >`) and multi-line quoted strings —
  the toolchain parser (`parseDoc`) throws "malformed line" on them; all
  multi-line text is now single-line (folded with spaces, content
  unchanged) or lives inside JSON blocks.
- The `# A1 …` comment on the customer-order lifecycle — comments don't
  survive the toolchain; its content is preserved as
  `payload.stateGates["replay-exception"].note`.

## Judgment calls

1. **Aggregate `identifier`**: pilot `identity.fields` was
   `[chain, customerId]`; the identifier is `customerId` (the idempotency
   key *within* a chain), with the full `(chain, customerId)` grain
   recorded in `identifier.origin`. (The task's own target shape shows
   `customerId`; the grain note keeps the composite fact.)
2. **`replay-exception` double lifecycle row**: pilot had
   `except_pool → credited` and `except_pool → paid` via the same command.
   JSON objects can't hold two values for one key, so
   `statusTransitions["replay-exception"]` and
   `stateGates["replay-exception"].toState` are `["credited", "paid"]`
   (chain-dependent) — the only multi-valued entries in either map.
3. **Batch-chain-run lifecycle `via` is event-driven**: rows like
   `{from: running, via: run-completed, to: completed}` reference events,
   not commands (the run's state moves when stage outcomes land). Mapped
   mechanically into `stateGates`/`statusTransitions` as-is.
4. **Read-model `source` derivations** (V11; vocabulary is
   `aggregateIdentity` | `eventField:<Event>.<field>`): rule of thumb —
   counts/partitions/assertions over an identity grain →
   `aggregateIdentity`; projections of a concrete event payload →
   `eventField:`. Per-field: chain-stats `scale`→`run-started.items`,
   `batches.running/queued`→`run-started.runId`,
   `batches.done`→`run-completed.runId`,
   `batches.recoveries`→`run-recovered.runId`,
   `per-chain.entered/inFlight/terminals/balanced`→`aggregateIdentity`
   (the `(chain × customer)` grain and its ledger assertion),
   `flow counters`/`feed`→`run-completed.runId` (observation-only);
   event-feed `frame`/`frame types`→`run-started.runId`;
   exception-pool `count`→`aggregateIdentity`,
   `entries[].chain/customerId/reason`→the `order-parked-in-exception-pool`
   fields, `entries[].name/at`→`order-parked-in-exception-pool.customerId`
   (pool-envelope fields materialized at park time, attributed to the
   parking event's key), `entries[].amount`→`order-credited.amount` (the
   parked order's amount). `sourceAggregate`: chain-stats and
   exception-pool → `Customer Order`; event-feed → `Batch Chain Run`
   (`sources` keeps the full list as aggregate names).
5. **Link vocabulary uses L1 note names** (PascalCase, e.g.
   `event:OrderCredited`, `command:SettleRecharge`,
   `readModel:ChainStatistics`) so CS1/CS3/CS7 resolve; the pilot's
   kebab ids (`order-credited`, `settle-recharge`, …) are kept verbatim
   in `payload.info.produces` / `can_issue` / `can_view` — same referents,
   two id dialects, both preserved.
6. **`board: reconstructed`** on every note/policy origin (the inbox was
   reconstructed from the tree; no live workshop was held — same claim
   the pilot banner made).
7. **Notices left standing (21, all report-only)**: CS6 — externalSystem
   `PartnerBankSystem` has no trigger link (pilot had none; not invented);
   CS8 — `story "CrashRecoveryDemo"` appears twice (the 11-stories story
   and the 12-use-cases use-case honestly share the name after the
   `useCase`→`story` type remap); AG1/AG2 ×19 — the L1 sticky names
   (PascalCase) deliberately differ from the slice/pool ids (kebab-case),
   so no inbox note is "owned" by an aggregate under the toolchain's
   exact-name ownership rule.

## Reproducibility

The migration was executed by script, not by hand:
`/tmp/migrate/build_spec{,2,3}.py` (pilot YAML → `spec.json`) +
`/tmp/migrate/emit_tree.mjs` (writes every atom through the toolchain's
own `emitDoc`, so output is byte-canonical by construction). Originals
used as input are at `git HEAD` (`git archive HEAD truth`).
