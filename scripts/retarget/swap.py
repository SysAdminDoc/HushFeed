"""swap.py <repo> --old=<version> --new=<version> --old-code=<code> --new-code=<code> [--write]

The old build's literals become the new build's: the version name, the version code, the
TIKTOK_<digits> constant and the dashed form, except on history lines and in release text.
A line is history (left alone) when it also names another build, or says what the old build changed.
Each literal is replaced only as a whole: 147.9.1, 47.9.1.5 and TIKTOK_47910 aren't 47.9.1.
"""
import pathlib
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
VALUED = ("old", "new", "old-code", "new-code")
args = [a for a in sys.argv[1:] if not a.startswith("--")]
opts: dict[str, str] = {}
for a in sys.argv[1:]:
    if a.startswith("--"):
        key, eq, value = a[2:].partition("=")
        if key == "write" and not eq:
            opts[key] = "1"
        elif key in VALUED and value:
            opts[key] = value
        else:
            sys.exit(f"--{key}{eq}{value}: unknown option, or a missing value\n\n{__doc__}")
if len(args) != 1 or not all(k in opts for k in VALUED):
    sys.exit(__doc__)
if not re.fullmatch(r"\d+(\.\d+)+", opts["old"]) or not re.fullmatch(r"\d+(\.\d+)+", opts["new"]) \
        or not opts["old-code"].isdigit() or not opts["new-code"].isdigit():
    sys.exit("--old and --new take a version name like 47.9.2, --old-code and --new-code its digits")
repo = pathlib.Path(args[0])
write = "write" in opts
OLD, NEW = opts["old"], opts["new"]


def whole(literal: str, before: str, after: str) -> re.Pattern:
    return re.compile(f"(?<!{before}){re.escape(literal)}(?!{after})")


# A version name isn't part of a longer one; a trailing period that ends a sentence is fine.
PAIRS = [(whole(OLD, r"[\d.]", r"\d|\.\d"), NEW),
         (whole(opts["old-code"], r"\d", r"\d"), opts["new-code"]),
         (whole("TIKTOK_" + OLD.replace(".", ""), r"\w", r"\d"), "TIKTOK_" + NEW.replace(".", "")),
         # APKMirror's slug: tiktok-47-9-1-release.
         (whole(OLD.replace(".", "-"), r"\d", r"\d|-\d"), NEW.replace(".", "-"))]
SKIP_PREFIX = ("l10n/", "CHANGELOG", "concepts/assets/", "audit/results/", "README.md", "patches-list.json", "patches-bundle.json",
               ".github/", "extensions/tiktok/src/main/l10n/notes/", "scripts/retarget/")
SKIP_NAME = ("ReleaseNotesData.java",)
SKIP_PART = ("/featuregatelab/Generated",)
BUILD = re.compile(r"(?<![\d.])\d{2,3}\.\d{1,2}\.\d{1,2}(?!\d|\.\d)")
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
    if not any(pattern.search(text) for pattern, _ in PAIRS):
        continue
    out = []
    dirty = False
    for i, line in enumerate(text.split("\n"), 1):
        if not any(pattern.search(line) for pattern, _ in PAIRS):
            out.append(line)
            continue
        others = {b for b in BUILD.findall(line) if b not in (OLD, NEW)}
        if others or CHANGE.search(line):
            history.append(f"{rel}:{i}: {line.strip()[:170]}")
            out.append(line)
            continue
        new = line
        for pattern, replacement in PAIRS:
            new = pattern.sub(replacement, new)
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
