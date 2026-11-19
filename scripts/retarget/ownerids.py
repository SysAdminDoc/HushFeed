"""ownerids.py <workdir> <old fixture dir> <new fixture dir> <prefix hex> <class>... : the resource literals each class
loads on the old [a] and new [b] build, in code order, with names from <workdir>'s res-old.tsv and res-new.tsv."""
import io
import pathlib
import struct
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
if len(sys.argv) < 6:
    sys.exit(__doc__)
HERE = pathlib.Path(sys.argv[1])


def only_apk(folder):
    found = sorted(pathlib.Path(folder).glob("*.apk"))
    if len(found) != 1:
        sys.exit(f"{folder} should hold one .apk, found {len(found)}")
    return found[0]


APKS = {"a": only_apk(sys.argv[2]), "b": only_apk(sys.argv[3])}
TABLES = {"a": "res-old.tsv", "b": "res-new.tsv"}


def uleb(d, at):
    v = s = 0
    while True:
        b = d[at]
        at += 1
        v |= (b & 0x7F) << s
        s += 7
        if not b & 0x80:
            return v, at


def literals(d, code_off, prefix, out):
    size = struct.unpack_from("<I", d, code_off + 12)[0]
    start = code_off + 16
    i = 0
    while i + 2 < size:
        unit = struct.unpack_from("<H", d, start + i * 2)[0]
        if unit & 0xFF == 0x14:
            v = struct.unpack_from("<I", d, start + i * 2 + 2)[0]
            if v >> 16 == prefix and v not in out:
                out.append(v)
            i += 3
            continue
        i += 1


def scan(d, wanted, prefix, found):
    ssize, soff, tsize, toff = struct.unpack_from("<4I", d, 56)
    csize, coff = struct.unpack_from("<2I", d, 96)
    def tname(i):
        sid = struct.unpack_from("<I", d, toff + i * 4)[0]
        off = struct.unpack_from("<I", d, soff + sid * 4)[0]
        _, st = uleb(d, off)
        return d[st:d.index(0, st)].decode("utf-8", "replace")
    for c in range(csize):
        rec = struct.unpack_from("<8I", d, coff + c * 32)
        name = tname(rec[0])
        if name not in wanted or not rec[6]:
            continue
        at = rec[6]
        sf, at = uleb(d, at)
        inf, at = uleb(d, at)
        dm, at = uleb(d, at)
        vm, at = uleb(d, at)
        for _ in range(sf + inf):
            _, at = uleb(d, at)
            _, at = uleb(d, at)
        out = found.setdefault(name, [])
        for _ in range(dm + vm):
            _, at = uleb(d, at)
            _, at = uleb(d, at)
            code, at = uleb(d, at)
            if code:
                literals(d, code, prefix, out)


def dexes(apk):
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if n.startswith("classes") and n.endswith(".dex"):
                yield z.read(n)
            elif n.startswith("lib/arm64-v8a/libdex_") and n.endswith(".so"):
                try:
                    with zipfile.ZipFile(io.BytesIO(z.read(n))) as inner:
                        for m in inner.namelist():
                            if m.endswith(".dex"):
                                yield inner.read(m)
                except zipfile.BadZipFile:
                    pass


prefix = int(sys.argv[4], 16)
wanted = {"L" + c.replace(".", "/") + ";" if not c.startswith("L") else c for c in sys.argv[5:]}
for side in "ab":
    names = {}
    for line in (HERE / TABLES[side]).read_text(encoding="utf-8").splitlines():
        p = line.split("\t")
        names[int(p[2], 16)] = p[1]
    found = {}
    for d in dexes(APKS[side]):
        scan(d, wanted, prefix, found)
    for cls in sorted(wanted):
        ids = found.get(cls)
        print(f"[{side}] {cls.rsplit('/', 1)[-1]} " + ("(class not found)" if ids is None else ""))
        if ids:
            print("   " + " ".join(f"{i + 1}:{names.get(v, '?')}" for i, v in enumerate(ids)))
