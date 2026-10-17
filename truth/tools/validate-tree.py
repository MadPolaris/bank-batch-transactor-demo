#!/usr/bin/env python3
"""validate-tree.py — structural gate for the bank-batch-transactor truth tree.

Philosophy (same as truthsrc's validate): the tool REPORTS, humans decide.
Hard errors = structural breakage (an agent consuming the tree would
hallucinate or crash). There are no "notices" here — if it's worth flagging,
it's worth fixing, because this tree is small and hand-maintained.

Checks:
  T1  every *.yaml parses
  T2  kind is in the types.yaml vocabulary; required fields present
  T3  id is globally unique; filename suffix matches kind
  T4  slice.aggregate resolves to an aggregate
  T5  every aggregate has >= 1 slice
  T6  policy trigger events resolve to events produced by some slice
  T7  policy invoked slices resolve
  T8  read-model sources resolve to aggregate/policy/pipeline ids
  T9  dotted type refs (e.g. batch-file.GeneratedFile) resolve their head id
  T10 tree.yaml index matches the ids actually present

Usage: python3 truth/tools/validate-tree.py truth/
Exit 0 = clean, 1 = hard errors.
"""
import sys
import yaml
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("truth")
errors = []


def err(rule, msg):
    errors.append(f"[{rule}] {msg}")


def load_types():
    with open(ROOT / "types.yaml") as f:
        doc = yaml.safe_load(f)
    vocab = {}
    for t in doc["types"]:
        vocab[t["kind"]] = t
    return vocab


def main():
    vocab = load_types()
    atoms = []  # (path, doc)
    for p in sorted(ROOT.rglob("*.yaml")):
        if "tools" in p.parts:
            continue
        try:
            doc = yaml.safe_load(p.read_text())
        except yaml.YAMLError as e:
            err("T1", f"{p.relative_to(ROOT)}: YAML parse error: {e}")
            continue
        if not isinstance(doc, dict):
            err("T1", f"{p.relative_to(ROOT)}: top level must be a mapping")
            continue
        atoms.append((p, doc))

    by_id = {}
    by_kind = {}
    produced_events = {}  # event id -> slice id
    for p, doc in atoms:
        rel = p.relative_to(ROOT)
        kind = doc.get("kind")
        if kind in ("tree", "types"):
            continue
        if kind not in vocab:
            err("T2", f"{rel}: unknown kind {kind!r}")
            continue
        spec = vocab[kind]
        for field in spec["required"]:
            if field not in doc:
                err("T2", f"{rel}: kind {kind!r} missing required field {field!r}")
        aid = doc.get("id")
        if not aid:
            err("T2", f"{rel}: id required")
            continue
        if aid in by_id:
            err("T3", f"duplicate id {aid!r}: {rel} vs {by_id[aid].relative_to(ROOT)}")
        else:
            by_id[aid] = p
        by_kind.setdefault(kind, {})[aid] = doc
        suffix = spec["file_suffix"]
        if suffix == "aggregate.yaml":
            if p.name != "aggregate.yaml":
                err("T3", f"{rel}: aggregate must live in aggregate.yaml")
        elif not p.name.endswith(suffix):
            err("T3", f"{rel}: kind {kind!r} file must end with {suffix!r}")
        if kind == "slice":
            for ev in doc.get("produces", []) or []:
                if ev in produced_events:
                    err("T6", f"event {ev!r} produced by two slices "
                              f"({produced_events[ev]} and {aid})")
                else:
                    produced_events[ev] = aid

    aggregates = by_kind.get("aggregate", {})
    slices = by_kind.get("slice", {})

    # T4/T5 — slice ↔ aggregate linkage
    slices_per_agg = {a: [] for a in aggregates}
    for sid, s in slices.items():
        agg = s.get("aggregate")
        if agg not in aggregates:
            err("T4", f"slice {sid!r}: aggregate {agg!r} resolves to nothing")
        else:
            slices_per_agg[agg].append(sid)
    for a in aggregates:
        if not slices_per_agg[a]:
            err("T5", f"aggregate {a!r} has no slices")

    # T6/T7 — policy refs
    for pid, pol in by_kind.get("policy", {}).items():
        for t in pol.get("triggers", []) or []:
            ev = t.get("event") if isinstance(t, dict) else t
            if ev not in produced_events:
                err("T6", f"policy {pid!r}: trigger event {ev!r} is not produced by any slice")
        for inv in pol.get("invokes", []) or []:
            s = inv.get("slice") if isinstance(inv, dict) else inv
            # may invoke a policy (reconfirm-rule, routing-decision) or a slice
            if s not in slices and s not in by_kind.get("policy", {}):
                err("T7", f"policy {pid!r}: invoked {s!r} is neither a slice nor a policy")

    # T8 — read-model sources
    resolvable = set(by_id)  # any atom id
    for rid, rm in by_kind.get("read-model", {}).items():
        for src in rm.get("sources", []) or []:
            if src not in resolvable:
                err("T8", f"read-model {rid!r}: source {src!r} resolves to nothing")

    # T9 — dotted type refs (head must be a value-object id)
    vos = set(by_kind.get("value-object", {}))
    for p, doc in atoms:
        rel = p.relative_to(ROOT)
        kind = doc.get("kind")
        if kind in ("tree", "types"):
            continue

        def check_type(t, where):
            if isinstance(t, str) and "." in t:
                head = t.split(".")[0]
                if head not in vos:
                    err("T9", f"{rel} {where}: type ref {t!r} head {head!r} "
                              f"is not a value-object id")

        for slot in doc.get("state_slots", []) or []:
            check_type(slot.get("type"), f"state_slot {slot.get('name')!r}")
        for param in doc.get("parameters", []) or []:
            check_type(param.get("type"), f"parameter {param.get('name')!r}")
        for ev, edef in (doc.get("events", {}) or {}).items():
            for pl in (edef or {}).get("payload", []) or []:
                check_type(pl.get("type"), f"event {ev!r} payload {pl.get('name')!r}")
        for var in doc.get("variants", []) or []:
            for f in var.get("fields", []) or []:
                check_type(f.get("type"), f"variant {var.get('name')!r} field {f.get('name')!r}")
        for f in doc.get("fields", []) or []:
            check_type(f.get("type"), f"field {f.get('name')!r}")

    # T10 — tree.yaml index matches reality
    tree_doc = next((d for _, d in atoms if d.get("kind") == "tree"), None)
    if tree_doc:
        index_ids = set()
        for key, ids in (tree_doc.get("index", {}) or {}).items():
            for i in ids or []:
                index_ids.add(i)
        real_ids = {d["id"] for _, d in atoms
                    if d.get("kind") not in ("tree", "types", "slice")}
        # slices are indexed implicitly via their aggregate's slices/ directory
        for i in sorted(index_ids - real_ids):
            err("T10", f"tree.yaml index lists {i!r} but no such atom exists")
        for i in sorted(real_ids - index_ids):
            err("T10", f"atom {i!r} exists but is missing from tree.yaml index")

    # ---- report ----
    kinds = {k: len(v) for k, v in by_kind.items()}
    total = sum(kinds.values())
    print(f"atoms: {total} " + " ".join(f"{k}={v}" for k, v in sorted(kinds.items())))
    print(f"events in pool: {len(produced_events)}")
    if errors:
        print(f"\n{len(errors)} HARD ERRORS:")
        for e in errors:
            print(f"  {e}")
        return 1
    print("validate: CLEAN — 0 hard errors")
    return 0


if __name__ == "__main__":
    sys.exit(main())
