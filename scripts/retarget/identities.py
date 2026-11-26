"""Mirror of ObfuscatedIdentityTest.scan(): print added/gone against the record, --write to update it.

Gone lines are deleted in place; added lines go after the last recorded line of the same file, or
at the sorted spot among entries when the file has none. Comment lines stay where they are.
"""
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")
REPO = sys.argv[1]
WRITE = "--write" in sys.argv
RECORD = os.path.join(REPO, "patches/src/test/resources/obfuscated-identities.txt")

A = re.ASCII
OBFUSCATED_TYPE = re.compile(r"L[XY]/[0-9A-Za-z_$]+;", A)
JAVA_BINARY_TYPE = re.compile(r"(?<![0-9A-Za-z_$])X\.[0-9A-Za-z]{4}(?![0-9A-Za-z_$])", A)
MEMBER_LITERAL = re.compile(r'"(L[IJLZF]{1,19})"', A)
# A date format's pattern ("LLLL" is the stand-alone month name), which the test drops first.
DATE_PATTERN = re.compile(r'SimpleDateFormat\(\s*"[^"]*"', A)
PREFIX_LITERAL = re.compile(r'"(LX/|LY/|Lkotlin/jvm/internal/A[A-Za-z]{1,3}S)', A)
SHORT_MEMBER_COMPARED = re.compile(r'name\s*==\s*"([A-Za-z][A-Za-z0-9]{0,2})"', A)
SHORT_RESOURCE = re.compile(r'"(?:[0-9]+(?:\.[0-9]+)+:)?([a-z][0-9][a-z0-9]{1,2}|[a-z]{2}[0-9])"', A)
RESOURCE_LOOKUP = re.compile(r"_IDS?\b|RESOURCE_NAME|getIdentifier\(|\.resolve\(|IDS\b|viewId\(|idOf\(", A)
RESOURCE_NAME = re.compile(r'"(?:[0-9]+(?:\.[0-9]+)+:)?([a-z][a-z0-9_]{1,2})"', A)
REAL_NAMES = {"get", "put", "run", "key", "id", "raw", "mp3", "mp4", "m4a", "m4v", "jpg", "png", "h264", "m10n"}


def identities(line, extension_tree):
    names = set()
    names.update(m.group(0) for m in OBFUSCATED_TYPE.finditer(line))
    names.update(m.group(0) for m in JAVA_BINARY_TYPE.finditer(line))
    names.update(m.group(1) for m in MEMBER_LITERAL.finditer(DATE_PATTERN.sub("", line)))
    for rx in (PREFIX_LITERAL, SHORT_MEMBER_COMPARED, SHORT_RESOURCE):
        names.update(m.group(1) for m in rx.finditer(line))
    if extension_tree and RESOURCE_LOOKUP.search(line):
        names.update(m.group(1) for m in RESOURCE_NAME.finditer(line))
    return names - REAL_NAMES


def scan():
    found = set()
    for rel, ext_tree in (("patches/src/main/kotlin", False), ("extensions/tiktok/src/main/java", True)):
        root = os.path.join(REPO, rel)
        for dirpath, _, files in os.walk(root):
            for name in files:
                if not (name.endswith(".kt") or name.endswith(".java")):
                    continue
                full = os.path.join(dirpath, name)
                path = os.path.relpath(full, REPO).replace(os.sep, "/")
                text = open(full, "rb").read().decode("utf-8")
                for raw in re.split(r"\r\n|\n|\r", text):
                    line = raw.strip()
                    if line.startswith("*") or line.startswith("//") or line.startswith("/*"):
                        continue
                    for ident in identities(line, ext_tree):
                        found.add(path + "|" + ident)
    return found


raw = open(RECORD, "rb").read().decode("utf-8")
crlf = "\r\n" in raw
lines = raw.replace("\r\n", "\n").split("\n")
trailing = lines and lines[-1] == ""
if trailing:
    lines = lines[:-1]
recorded = {l.strip() for l in lines if l.strip() and not l.strip().startswith("#")}
found = scan()
added = sorted(found - recorded)
gone = sorted(recorded - found)
print("added %d" % len(added))
for a in added:
    print("  + " + a)
print("gone %d" % len(gone))
for g in gone:
    print("  - " + g)
if WRITE and (added or gone):
    kept = [l for l in lines if l.strip() not in set(gone)]
    for entry in added:
        path = entry.split("|", 1)[0]
        same = [i for i, l in enumerate(kept) if l.strip().split("|", 1)[0] == path and not l.startswith("#")]
        if same:
            kept.insert(same[-1] + 1, entry)
            continue
        entries = [i for i, l in enumerate(kept) if l.strip() and not l.startswith("#")]
        after = [i for i in entries if kept[i].strip() > entry]
        kept.insert(after[0] if after else len(kept), entry)
    out = "\n".join(kept) + ("\n" if trailing else "")
    if crlf:
        out = out.replace("\n", "\r\n")
    open(RECORD, "wb").write(out.encode("utf-8"))
    print("written")
