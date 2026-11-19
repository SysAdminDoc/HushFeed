"""viewmap.py <workdir> <names.txt> : old view name -> new view name through Propagate's R rows (old id -> new id by votes).
<workdir> holds res-old.tsv, res-new.tsv (restable.py) and propagate.tsv; only the base package's id entries count."""
import pathlib
import sys

sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
if len(sys.argv) != 3:
    sys.exit(__doc__)
d = pathlib.Path(sys.argv[1])
names = [n.strip() for n in pathlib.Path(sys.argv[2]).read_text(encoding="utf-8").split() if n.strip()]


def table(p):
    by_name, by_id = {}, {}
    for line in p.read_text(encoding="utf-8").splitlines():
        kind, n, v = line.split("\t")
        if kind != "id" or not v.startswith("0x7f"):
            continue
        by_name[n] = int(v, 16)
        by_id[int(v, 16)] = n
    return by_name, by_id


old_n, old_i = table(d / "res-old.tsv")
new_n, new_i = table(d / "res-new.tsv")
rows = {}
for line in (d / "propagate.tsv").read_text(encoding="utf-8").splitlines():
    p = line.split("\t")
    if p[0] != "R":
        continue
    a, b = int(p[1], 16) if p[1].startswith("0x") else int(p[1]), int(p[2], 16) if p[2].startswith("0x") else int(p[2])
    rows.setdefault(a, []).append((b, int(p[3]), int(p[4])))
for n in names:
    oid = old_n.get(n)
    if oid is None:
        print(f"{n}\t-\tNOT IN THE OLD BUILD'S ID TABLE")
        continue
    cands = sorted(rows.get(oid, []), key=lambda r: -r[1])
    if not cands:
        print(f"{n}\t?\told {oid:#x}, no R row (same index would be {new_i.get(oid, '?')})")
        continue
    best = cands[0]
    alt = "; ".join(f"{new_i.get(b, '?')}={v}/{t}" for b, v, t in cands[1:4])
    print(f"{n}\t{new_i.get(best[0], '?')}\t{best[1]}/{best[2]}\told {oid:#x} new {best[0]:#x}{(' | alt ' + alt) if alt else ''}")
