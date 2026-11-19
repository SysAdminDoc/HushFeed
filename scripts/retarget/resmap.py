"""resmap.py <workdir> <name...> : each old-build resource name, in every type that has it, with its new-build name
through the tracer's id rows. <workdir> holds res-old.tsv, res-new.tsv (restable.py) and propagate.tsv."""
import pathlib
import sys

sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
if len(sys.argv) < 3:
    sys.exit(__doc__)
W = pathlib.Path(sys.argv[1])


def load(path):
    by_id, by_name = {}, {}
    for line in path.read_text(encoding="utf-8").splitlines():
        kind, name, rid = line.split("\t")
        by_id[rid] = (kind, name)
        by_name.setdefault(name, []).append((kind, rid))
    return by_id, by_name


_, old_names = load(W / "res-old.tsv")
new_ids, new_names = load(W / "res-new.tsv")
rows = {}
for line in (W / "propagate.tsv").read_text(encoding="utf-8").splitlines():
    p = line.split("\t")
    if p[0] == "R" and len(p) >= 5:
        rows.setdefault(p[1], (p[2], p[3], p[4]))

for name in sys.argv[2:]:
    hits = old_names.get(name)
    if not hits:
        print(f"{name}: not in the old build (the new one has {new_names.get(name)})")
    for kind, rid in hits or []:
        row = rows.get(rid)
        if not row:
            print(f"{name}: {kind} {rid} no row")
            continue
        now = new_ids.get(row[0])
        print(f"{name}: {kind} {rid} -> {row[0]} {now[1] if now else '?'} ({now[0] if now else '?'}) votes {row[1]}/{row[2]}")
