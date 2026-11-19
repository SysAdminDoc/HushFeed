"""owners.py <repo> <out> : every LX/LY class a tracked code file names (Lx/y; or dotted X.name), for Propagate's member rows."""
import pathlib
import re
import subprocess
import sys

repo, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
EXT = (".kt", ".kts", ".java", ".json", ".txt", ".py", ".ps1", ".md", ".smali", ".xml")
SKIP_PREFIX = ("l10n/", "CHANGELOG", "concepts/assets/", "audit/results/")
TOKEN = re.compile(r"L([XY])/([A-Za-z0-9_$]+);|(?<![\w.$/])([XY])\.([0-9A-Za-z_$]{2,})(?![\w$])")
files = subprocess.run(["git", "-C", str(repo), "ls-files"], capture_output=True, text=True, encoding="utf-8").stdout.splitlines()
names = set()
for rel in files:
    if not rel.endswith(EXT) or rel.startswith(SKIP_PREFIX):
        continue
    try:
        text = (repo / rel).read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    for m in TOKEN.finditer(text):
        names.add(f"L{m.group(1)}/{m.group(2)};" if m.group(1) else f"L{m.group(3)}/{m.group(4)};")
out.write_text("\n".join(sorted(names)) + "\n", encoding="utf-8", newline="\n")
print(f"{len(names)} owners -> {out}")
