"""Sacrifice 2.5 probe: start a vanilla 26.3 server with the pack, locate each ring, generate a radial strip of terrain
from the Lake of Fire out to the Wall plus a patch of Titan Wood and Giants' Reach, then save the region files to
test/out so the terrain can be rendered offline. Also locates every structure."""
import subprocess, re, time, threading, queue, math, os, shutil, json

D = "sacrifice:sacrifice"
p = subprocess.Popen(["java", "-Xmx6G", "-jar", "server.jar", "nogui"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
q = queue.Queue(); log = open("server.log", "w")
def pump():
    for line in p.stdout:
        log.write(line); log.flush(); q.put(line)
threading.Thread(target=pump, daemon=True).start()
def wait_for(pat, timeout=600):
    end = time.time() + timeout
    while time.time() < end:
        try: line = q.get(timeout=1)
        except queue.Empty: continue
        m = re.search(pat, line)
        if m: return m, line
    return None, None
def cmd(c, pat=None, timeout=120):
    while not q.empty(): q.get_nowait()
    p.stdin.write(c + "\n"); p.stdin.flush()
    if pat: return wait_for(pat, timeout)

m, _ = wait_for(r"Done \(", 900)
report = [f"server up: {bool(m)}"]
loc = {}
import zipfile
PACK = os.environ["PACK"]; OUTD = f"../test/out/{os.path.splitext(os.path.basename(PACK))[0]}"
dimj = json.loads(zipfile.ZipFile(PACK).read("data/sacrifice/dimension/sacrifice.json"))
RINGS = [b["biome"].split(":")[1] for b in dimj["generator"]["biome_source"]["biomes"] if b["parameters"]["continentalness"][1] > 0.2]
report.append("rings: " + ", ".join(RINGS))
for b in RINGS:
    m, line = cmd(f"execute in {D} positioned 0 80 0 run locate biome sacrifice:{b}", r"(is at \[(-?\d+), [~\d-]+, (-?\d+)\]|Could not|rror|nknown)", 300)
    if m and m.group(2):
        loc[b] = (int(m.group(2)), int(m.group(3))); report.append(f"biome {b}: {loc[b]}  dist {int(math.hypot(*loc[b]))}")
    else: report.append(f"biome {b}: {line.strip() if line else 'timeout'}")
for s in ["titan_tree", "blood_tree", "undergrowth", "giant_sword", "giant_footprint", "giant_campfire", "giant_mushroom", "bone_remains", "calf"]:
    m, line = cmd(f"execute in {D} positioned 0 80 0 run locate structure sacrifice:{s}", r"(is at \[(-?\d+), [~\d-]+, (-?\d+)\]|Could not|rror|nknown)", 300)
    report.append(f"structure {s}: " + (f"{m.group(2)},{m.group(3)}" if m and m.group(2) else (line.strip()[-90:] if line else "timeout")))

# a radial strip, 3 chunks wide, through the Titan Wood point, from just inside the lake to past the wall
chunks = set()
if RINGS[1] in loc:
    tx, tz = loc[RINGS[1]]; r = math.hypot(tx, tz); ux, uz = tx / r, tz / r
    r0 = r * 0.97
    r1 = (math.hypot(*loc[RINGS[-1]]) + 160) if RINGS[-1] in loc else r * 1.6
    d = r0
    while d <= r1:
        cx, cz = math.floor(ux * d / 16), math.floor(uz * d / 16)
        for ox in (-1, 0, 1):
            for oz in (-1, 0, 1): chunks.add((cx + ox, cz + oz))
        d += 8
    for name in RINGS[2:-1]:   # and an 8x8-chunk patch of each inner ring
        if name in loc:
            bx, bz = loc[name][0] // 16, loc[name][1] // 16
            for ox in range(-4, 4):
                for oz in range(-4, 4): chunks.add((bx + ox, bz + oz))
report.append(f"generating {len(chunks)} chunks")
batch = sorted(chunks)
for i in range(0, len(batch), 48):
    for cx, cz in batch[i:i + 48]: cmd(f"execute in {D} run forceload add {cx * 16} {cz * 16}")
    time.sleep(12)
time.sleep(30)
cmd("save-all flush", r"(Saved the game|aved)", 300); time.sleep(5)
cmd("stop"); p.wait(timeout=600)

# keep the region files that hold the strip and patches
src = "world/dimensions/sacrifice/sacrifice/region"
if not os.path.isdir(src):
    for root, dirs, files in os.walk("world"):
        if root.endswith("region") and "sacrifice" in root: src = root; break
os.makedirs(OUTD, exist_ok=True)
need = {(cx >> 5, cz >> 5) for cx, cz in chunks}
for rx, rz in need:
    f = f"{src}/r.{rx}.{rz}.mca"
    if os.path.exists(f): shutil.copy(f, f"{OUTD}/r.{rx}.{rz}.mca")
json.dump({"loc": loc, "rings": RINGS, "chunks": sorted(chunks)}, open(f"{OUTD}/meta.json", "w"))
report.append(f"region dir {src}; saved {len(os.listdir(OUTD)) - 1} region files")
print("\n===== REPORT =====", flush=True)
for r in report: print(r, flush=True)
print("===== WARN/ERROR lines =====", flush=True)
for line in open("server.log"):
    if (("WARN" in line or "ERROR" in line) and "sacrifice" in line.lower()) or "Exception" in line or "Failed to" in line or "Couldn't" in line: print(line.rstrip()[:500])
