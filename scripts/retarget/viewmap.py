"""viewmap.py: old view name -> new view name through Propagate's R rows (old id -> new id by votes).

  viewmap.py <workdir> <names.txt>
      Prints each name's best match and the votes behind it.
  viewmap.py <workdir> --repo=<root> --build=<version> [--write]
      Finds every `<version>:<name>` in the tracked files (the names the extension looks up on the
      phone, after swap.py moved their prefix to the new build) and renames each name the tracer
      matched with every vote, in one pass so a new name that is also an old name moves once.
      Names it can't match are listed for a pick by hand: RuntimeViewIdAnchorsTest names what each
      group's owner and tell pick on the new build.

<workdir> holds res-old.tsv, res-new.tsv (restable.py) and propagate.tsv; only the base package's
id entries count."""
import pathlib
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
args = [a for a in sys.argv[1:] if not a.startswith("--")]
opts: dict[str, str] = {}
for a in sys.argv[1:]:
    if a.startswith("--"):
        key, eq, value = a[2:].partition("=")
        if key == "write" and not eq:
            opts[key] = "1"
        elif key in ("repo", "build") and value:
            opts[key] = value
        else:
            sys.exit(f"--{key}{eq}{value}: unknown option, or a missing value\n\n{__doc__}")
repo_mode = "repo" in opts or "build" in opts
if repo_mode and (len(args) != 1 or "repo" not in opts or "build" not in opts):
    sys.exit(__doc__)
if not repo_mode and (len(args) != 2 or "write" in opts):
    sys.exit(__doc__)
if repo_mode and not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", opts["build"]):
    sys.exit("--build takes a version name like 48.0.2")
d = pathlib.Path(args[0])


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


def match(n):
    """(new name or None, report line). A name comes back only when every vote agreed on it."""
    oid = old_n.get(n)
    if oid is None:
        return None, f"{n}\t-\tNOT IN THE OLD BUILD'S ID TABLE"
    cands = sorted(rows.get(oid, []), key=lambda r: -r[1])
    if not cands:
        return None, f"{n}\t?\told {oid:#x}, no R row (same index would be {new_i.get(oid, '?')})"
    best = cands[0]
    alt = "; ".join(f"{new_i.get(b, '?')}={v}/{t}" for b, v, t in cands[1:4])
    name = new_i.get(best[0])
    line = f"{n}\t{name or '?'}\t{best[1]}/{best[2]}\told {oid:#x} new {best[0]:#x}{(' | alt ' + alt) if alt else ''}"
    return (name if name and best[1] == best[2] and len(cands) == 1 else None), line


if not repo_mode:
    for n in pathlib.Path(args[1]).read_text(encoding="utf-8").split():
        print(match(n.strip())[1])
    sys.exit(0)

root = pathlib.Path(opts["repo"])
build = opts["build"]
token = re.compile(r"(?<![0-9.])" + re.escape(build) + r":([A-Za-z0-9_]+)")
listed = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=True).stdout
texts = {}
for rel in filter(None, listed.decode("utf-8").split("\0")):
    try:
        text = (root / rel).read_bytes().decode("utf-8")
    except (OSError, UnicodeDecodeError):
        continue
    if token.search(text):
        texts[rel] = text
names = sorted({m.group(1) for text in texts.values() for m in token.finditer(text)})
mapping, unmapped = {}, []
for n in names:
    new_name, line = match(n)
    if new_name is None:
        unmapped.append(line)
    elif new_name != n:
        mapping[n] = new_name
total = 0
for rel, text in sorted(texts.items()):
    hits = sum(1 for m in token.finditer(text) if m.group(1) in mapping)
    if not hits:
        continue
    total += hits
    print(f"{hits:4d}  {rel}")
    if "write" in opts:
        renamed = token.sub(lambda m: f"{build}:{mapping.get(m.group(1), m.group(1))}", text)
        (root / rel).write_bytes(renamed.encode("utf-8"))
print(f"== renamed {len(mapping)} of {len(names)} names, {total} places{'' if 'write' in opts else ' (dry run)'}")
for line in unmapped:
    print(f"unmapped {line}")
if unmapped:
    print(f"== {len(unmapped)} names to pick by hand: RuntimeViewIdAnchorsTest names what each owner and tell pick on {build}")
