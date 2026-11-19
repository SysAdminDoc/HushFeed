"""restable.py <aapt2> <apk> <out.tsv> : writes "type<TAB>name<TAB>0x..." for every resource of every package in the APK,
via aapt2 dump resources. resmap.py and viewmap.py read two of these (res-old.tsv, res-new.tsv) beside propagate.tsv."""
import subprocess
import sys

if len(sys.argv) != 4:
    sys.exit(__doc__)
aapt2, apk, out = sys.argv[1:4]
run = subprocess.run([aapt2, "dump", "resources", apk], capture_output=True, text=True, encoding="utf-8", errors="replace")
if run.returncode != 0:
    sys.exit(f"aapt2 dump resources failed ({run.returncode}): {run.stderr.strip()[:300]}")
count = 0
with open(out, "w", encoding="utf-8", newline="\n") as f:
    for line in run.stdout.splitlines():
        parts = line.split()
        # "    resource 0x7f0a0000 id/a17"
        if len(parts) >= 3 and parts[0] == "resource" and parts[1].startswith("0x") and "/" in parts[2]:
            kind, name = parts[2].split("/", 1)
            f.write(f"{kind}\t{name}\t{parts[1]}\n")
            count += 1
if not count:
    sys.exit(f"aapt2 listed no resources for {apk}")
print(f"{count} resources -> {out}")
