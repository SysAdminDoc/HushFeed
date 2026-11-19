"""rename.py <propagate.tsv> <repo> --old=<version> --new=<version> [--extra=classes.tsv] [--write]
One pass over every tracked code line that names no build but the one being left (--old):
  1. member refs written Lowner;->name(desc) / Lowner;->name:type get the tracer's new member name
     (the owner's M/F row with that exact descriptor, or the only row for that name),
  2. then every LX/LY class token (and dotted X.name / Y.name) gets its new class name.
Members first, classes second, in the same substitution, so nothing is renamed twice.
--extra adds "old new" class pairs (ClassMatch picks) on top of the tracer's accepted and seed rows.
Prints the counts, history lines skipped, lines naming the old build, unmapped names, unresolved member refs,
and quoted strings equal to a renamed member of a class the same file names (read those by hand)."""
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
        elif key in ("old", "new", "extra") and value:
            opts[key] = value
        else:
            sys.exit(f"--{key}{eq}{value}: unknown option, or a missing value\n\n{__doc__}")
if len(args) != 2 or "old" not in opts or "new" not in opts:
    sys.exit(__doc__)
write = "write" in opts
tsv, repo = pathlib.Path(args[0]), pathlib.Path(args[1])
OLD_BUILD = opts["old"]
NEW_BUILD = opts["new"]

cmap = {}
members = {}  # (owner, name) -> {desc: (newName, newDesc, votes, total)}
for line in tsv.read_text(encoding="utf-8").splitlines():
    p = line.split("\t")
    if p[0] == "C" and p[5] in ("accepted", "seed"):
        cmap[p[1]] = p[2]
    elif p[0] in ("M", "F"):
        members.setdefault((p[1], p[2]), {})[p[3]] = (p[5], p[6], int(p[7]), int(p[8]))
if "extra" in opts:
    for line in pathlib.Path(opts["extra"]).read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) >= 2 and not line.startswith("#"):
            cmap[parts[0]] = parts[1]

SKIP_PREFIX = ("l10n/", "CHANGELOG", "concepts/assets/", "audit/results/", "scripts/retarget/")
SKIP_PART = ("/accountmanagement/", "AccountManagement")
EXT = (".kt", ".kts", ".java", ".json", ".txt", ".py", ".ps1", ".md", ".smali", ".xml")
# Any build named on the line, v47.8.3 and a sentence-final 47.8.3. included.
BUILD = re.compile(r"(?<![\d.])\d{2,3}\.\d{1,2}\.\d{1,2}(?!\d|\.\d)")
OLD_WHOLE = re.compile(r"(?<![\d.])" + re.escape(OLD_BUILD) + r"(?!\d|\.\d)")
TOKEN = re.compile(r"L([XY])/([A-Za-z0-9_$]+);|(?<![\w.$/])([XY])\.([0-9A-Za-z_$]{2,})(?![\w$])")
ARROW = re.compile(r"(L[XY]/[A-Za-z0-9_$]+;)->([A-Za-z0-9_$]+)(\([^)\s]*\)\[*(?:L[^;\s]+;|[VZBSCIJFD])|:\[*(?:L[^;\s]+;|[ZBSCIJFD]))?")
QUOTED = re.compile(r'"([A-Za-z0-9_$]{1,24})"')

changed_names = {}
for (owner, name), rows in members.items():
    for desc, (new_name, new_desc, votes, total) in rows.items():
        if new_name != name:
            changed_names.setdefault(owner, {})[name] = new_name

files = subprocess.run(["git", "-C", str(repo), "ls-files"], capture_output=True, text=True, encoding="utf-8").stdout.splitlines()
history, review, unmapped, unresolved, quoted, per_file, skipped = [], [], {}, [], [], {}, []
member_hits = 0
for rel in files:
    if not rel.endswith(EXT) or rel.startswith(SKIP_PREFIX):
        continue
    if any(s in rel for s in SKIP_PART):
        try:
            if TOKEN.search((repo / rel).read_text(encoding="utf-8")):
                skipped.append(rel)
        except (UnicodeDecodeError, OSError):
            pass
        continue
    path = repo / rel
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    crlf = b"\r\n" in raw
    lines = text.replace("\r\n", "\n").split("\n")
    owners_here = {f"L{m.group(1)}/{m.group(2)};" for m in TOKEN.finditer(text) if m.group(1)}
    changed = 0
    for i, line in enumerate(lines):
        if not TOKEN.search(line):
            if owners_here:
                for q in QUOTED.finditer(line):
                    for owner in owners_here:
                        new = changed_names.get(owner, {}).get(q.group(1))
                        if new:
                            quoted.append(f"{rel}:{i + 1}: \"{q.group(1)}\" of {owner} -> {new} | {line.strip()[:110]}")
            continue
        builds = set(BUILD.findall(line))
        if builds - {OLD_BUILD}:
            history.append(f"{rel}:{i + 1}: {line.strip()[:150]}")
            continue

        def arrow(m):
            global member_hits
            owner, name, desc = m.group(1), m.group(2), m.group(3)
            rows = members.get((owner, name))
            if not rows:
                if owner in cmap and name in changed_names.get(owner, {}):
                    unresolved.append(f"{rel}:{i + 1}: {m.group(0)} (no row, name changed elsewhere)")
                return m.group(0)
            if desc:
                key = desc[1:] if desc.startswith(":") else desc
                row = rows.get(key)
                if row is None:
                    unresolved.append(f"{rel}:{i + 1}: {m.group(0)} (descriptor not in tracer rows)")
                    return m.group(0)
            else:
                news = {r[0] for r in rows.values()}
                if len(news) != 1:
                    unresolved.append(f"{rel}:{i + 1}: {m.group(0)} (overloads disagree: {sorted(news)})")
                    return m.group(0)
                row = next(iter(rows.values()))
            if row[0] != name:
                member_hits += 1
            return f"{owner}->{row[0]}{desc or ''}"

        out = ARROW.sub(arrow, line)

        def sub(m):
            old = f"L{m.group(1)}/{m.group(2)};" if m.group(1) else f"L{m.group(3)}/{m.group(4)};"
            new = cmap.get(old)
            if new is None:
                if m.group(1):
                    unmapped.setdefault(old, []).append(f"{rel}:{i + 1}")
                return m.group(0)
            return new if m.group(1) else new[1:-1].replace("/", ".")

        out = TOKEN.sub(sub, out)
        if OLD_BUILD in builds:
            out = OLD_WHOLE.sub(NEW_BUILD, out)
            review.append(f"{rel}:{i + 1}: {line.strip()[:150]}")
        if out != line:
            lines[i] = out
            changed += 1
    if changed:
        per_file[rel] = changed
        if write:
            body = "\n".join(lines)
            if crlf:
                body = body.replace("\n", "\r\n")
            path.write_bytes(body.encode("utf-8"))

print(f"== {len(per_file)} files, {sum(per_file.values())} lines changed, {member_hits} member renames{' (written)' if write else ' (dry run)'}")
print(f"== {len(history)} history lines skipped")
for h in history:
    print("  H", h)
print(f"== {len(review)} lines naming {OLD_BUILD} (changed, read them)")
for r in review:
    print("  R", r)
print(f"== {len(unresolved)} member refs left alone")
for u in unresolved:
    print("  M", u)
print(f"== {len(quoted)} quoted strings equal to a renamed member of a class the file names")
for q in quoted:
    print("  Q", q)
print(f"== {len(skipped)} account management files naming LX/LY classes, left alone (move them by hand)")
for s in skipped:
    print("  S", s)
print(f"== {len(unmapped)} class names with no mapping, outside history")
for name, where in sorted(unmapped.items(), key=lambda kv: -len(kv[1])):
    print(f"  U {name} x{len(where)} {where[:3]}")
