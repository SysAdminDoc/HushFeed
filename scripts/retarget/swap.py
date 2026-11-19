"""swap.py <repo> --old=<version> --new=<version> --old-code=<code> --new-code=<code> [--write]

The old build's literals become the new build's: the version name, the version code, the
TIKTOK_<digits> constant and the dashed form, except on history lines and in release text.
A line is history (left alone) when it also names another build, or says what the old build changed.
"""
import pathlib
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
args = [a for a in sys.argv[1:] if not a.startswith("--")]
opts: dict[str, str] = {}
for a in sys.argv[1:]:
    if a.startswith("--"):
        key, _, value = a[2:].partition("=")
        opts[key] = value or "1"
if len(args) != 1 or not all(k in opts for k in ("old", "new", "old-code", "new-code")):
    sys.exit(__doc__)
repo = pathlib.Path(args[0])
write = "write" in opts
OLD, NEW = opts["old"], opts["new"]
PAIRS = [(OLD, NEW), (opts["old-code"], opts["new-code"]),
         ("TIKTOK_" + OLD.replace(".", ""), "TIKTOK_" + NEW.replace(".", "")),
         (OLD.replace(".", "-"), NEW.replace(".", "-"))]
SKIP_PREFIX = ("l10n/", "CHANGELOG", "concepts/assets/", "audit/results/", "README.md", "patches-list.json", "patches-bundle.json",
               ".github/", "extensions/tiktok/src/main/l10n/notes/", "scripts/retarget/")
SKIP_NAME = ("ReleaseNotesData.java",)
SKIP_PART = ("/featuregatelab/Generated",)
BUILD = re.compile(r"(?<![\d.])4[5-9]\.\d{1,2}\.\d{1,2}(?![\d.])")
old = re.escape(OLD)
CHANGE = re.compile(old + r" (added|adds|inlined|dropped|drops|moved|moves|renamed|put|puts|split|merged|changed|stopped|started|"
                    r"introduced|removed|replaced|swapped|brought|gave|took|turned|reshuffled|shifted|reordered|folded|outlined|new)\b"
                    r"|(since|until|before|after|from|through|as of|up to) " + old + r"\b|" + old + r" (also |now |again )")
files = subprocess.run(["git", "-C", str(repo), "ls-files"], capture_output=True, text=True, encoding="utf-8").stdout.splitlines()
changed, history = [], []
nfiles = 0
for rel in files:
    if rel.startswith(SKIP_PREFIX) or rel.endswith(SKIP_NAME) or any(p in rel for p in SKIP_PART):
        continue
    path = repo / rel
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    if not any(a in text for a, _ in PAIRS):
        continue
    out = []
    dirty = False
    for i, line in enumerate(text.split("\n"), 1):
        if not any(a in line for a, _ in PAIRS):
            out.append(line)
            continue
        others = {b for b in BUILD.findall(line) if b not in (OLD, NEW)}
        if others or CHANGE.search(line):
            history.append(f"{rel}:{i}: {line.strip()[:170]}")
            out.append(line)
            continue
        new = line
        for a, b in PAIRS:
            new = new.replace(a, b)
        changed.append(f"{rel}:{i}: {new.strip()[:170]}")
        out.append(new)
        dirty = True
    if dirty:
        nfiles += 1
        if write:
            path.write_bytes("\n".join(out).encode("utf-8"))
print(f"== {nfiles} files, {len(changed)} lines {'written' if write else '(dry run)'}")
print(f"== {len(history)} history lines left alone")
for h in history:
    print("  H " + h)
print("== changed")
for c in changed:
    print("  C " + c)
