"""optprofiles.py <repo> <fixture dir> [--write] : every file(path, sha) in ResourceOptimizerPatches.kt against the
new build's universal APK and .apkm bundle (the fixture folder holds one of each). --write moves the digests of
files whose bytes changed; a file that's gone, or that the bundle lacks, is printed for a hand edit."""
import hashlib
import io
import pathlib
import re
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
args = [a for a in sys.argv[1:] if not a.startswith("--")]
if len(args) != 2:
    sys.exit(__doc__)
repo, fixture = pathlib.Path(args[0]), pathlib.Path(args[1])


def only(pattern):
    found = sorted(fixture.glob(pattern))
    if len(found) != 1:
        sys.exit(f"{fixture} should hold one {pattern}, found {len(found)}")
    return found[0]


APK, APKM = only("*.apk"), only("*.apkm")
KT = repo / "patches/src/main/kotlin/app/morphe/patches/tiktok/misc/optimizer/ResourceOptimizerPatches.kt"
FILE = re.compile(r'file\("([^"]+)", "([0-9a-f]{64})"\)')
text = KT.read_bytes().decode("utf-8")
listed = FILE.findall(text)
want = {p for p, _ in listed}


def sha(b):
    return hashlib.sha256(b).hexdigest()


apk = {}
with zipfile.ZipFile(APK) as z:
    names = set(z.namelist())
    for p in want & names:
        apk[p] = sha(z.read(p))
bundle = {}
with zipfile.ZipFile(APKM) as z:
    for i in z.infolist():
        if not i.filename.endswith(".apk"):
            continue
        with zipfile.ZipFile(io.BytesIO(z.read(i))) as inner:
            for p in want & set(inner.namelist()):
                bundle[p] = sha(inner.read(p))
same, moved, gone = 0, [], []
for p, h in listed:
    if p not in apk:
        gone.append(p)
    elif apk[p] == h:
        same += 1
    else:
        moved.append((p, h, apk[p]))
print(f"listed {len(listed)} same {same} moved {len(moved)} gone-from-apk {len(gone)}")
for p, a, b in moved:
    print("MOVED", p, a[:12], "->", b[:12])
for p in gone:
    print("GONE", p)
print("-- in the APK, absent from the bundle:")
for p in sorted(want):
    if p in apk and p not in bundle:
        print("ABSENT", p)
print("-- bundle copy differs from the APK's:")
for p in sorted(want):
    if p in apk and p in bundle and apk[p] != bundle[p]:
        print("DIFF", p)
if "--write" in sys.argv and moved:
    for p, a, b in moved:
        text = text.replace(f'file("{p}", "{a}")', f'file("{p}", "{b}")')
    KT.write_bytes(text.encode("utf-8"))
    print("written")
