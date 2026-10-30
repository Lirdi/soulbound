"""Starts a vanilla server with the Sacrifice pack, locates each structure in the Sacrifice dimension,
loads the chunks there and counts the structure's blocks that were really placed."""
import subprocess, re, time, sys, threading, queue

STRUCTS = [  # structure, block that only it places, block to swap it for while counting
    ("titan_tree", "minecraft:crimson_stem"), ("blood_tree", "minecraft:crimson_stem"), ("bone_tree", "minecraft:end_rod"),
    ("willow", "minecraft:mangrove_log"), ("huge_fungus", "minecraft:shroomlight"), ("giant_mushroom", "minecraft:mushroom_stem"),
    ("giant_sword", "minecraft:iron_block"), ("bone_remains", "minecraft:bone_block"), ("giant_campfire", "minecraft:dark_oak_log"),
    ("calf", "minecraft:polished_blackstone_bricks"), ("bloodaltar", "minecraft:red_nether_bricks")]
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
print("server up:", bool(m), flush=True)
report = []
for sid, block in STRUCTS:
    m, line = cmd(f"execute in {D} run locate structure sacrifice:{sid}", r"(nearest .* is at \[(-?\d+), [~\d-]+, (-?\d+)\]|Could not find|nknown|Incorrect)", 300)
    if not m or not m.group(2): report.append(f"{sid}: LOCATE -> {line.strip() if line else 'timeout'}"); continue
    x, z = int(m.group(2)), int(m.group(3))
    cmd(f"execute in {D} run forceload add {x - 48} {z - 48} {x + 48} {z + 48}", r"(Marked|forceload|chunk)", 60)
    time.sleep(25)
    total = 0
    for y in range(-60, 320, 18):
        mm, l2 = cmd(f"execute in {D} run fill {x - 40} {y} {z - 40} {x + 40} {min(y + 17, 319)} {z - 36} minecraft:bedrock replace {block}", r"(Successfully filled (\d+)|No blocks were filled|too many|not loaded|annot)", 60)
    # count over the whole area in slabs small enough for /fill
    total = 0; errs = set()
    for zz in range(z - 40, z + 41, 8):
        for y in range(-60, 320, 48):
            mm, l2 = cmd(f"execute in {D} run fill {x - 40} {y} {zz} {x + 40} {min(y + 47, 319)} {min(zz + 7, z + 40)} minecraft:bedrock replace {block}", r"(Successfully filled (\d+)|No blocks were filled|too many|not loaded|annot|rror)", 60)
            if mm and mm.group(2): total += int(mm.group(2))
            elif mm and "No blocks" not in mm.group(1): errs.add(l2.strip()[-80:])
    hm, hl = cmd(f"execute in {D} positioned {x} 0 {z} run locate biome sacrifice:titan_wood", r"(nearest|Could not)", 120)
    report.append(f"{sid}: at {x},{z}  {block} blocks found: {total}  {sorted(errs)[:2]}")
    cmd(f"execute in {D} run forceload remove all", None); time.sleep(2)
# direct placement checks
cmd(f"execute in {D} run forceload add 6000 0 6040 40", None); time.sleep(20)
m, l = cmd(f"execute in {D} run place template sacrifice:titan_tree_1 6000 150 0", r"(laced|ailed|nvalid|rror)", 60); report.append(f"place template titan_tree_1: {l.strip() if l else 'timeout'}")
m, l = cmd(f"execute in {D} run place structure sacrifice:titan_tree 6020 150 20", r"(enerated|ailed|nvalid|rror)", 60); report.append(f"place structure titan_tree: {l.strip() if l else 'timeout'}")
cmd("stop"); p.wait(timeout=300)
print("\n===== REPORT =====", flush=True)
for r in report: print(r, flush=True)
print("===== WARN/ERROR lines =====", flush=True)
for line in open("server.log"):
    if ("WARN" in line or "ERROR" in line) and "sacrifice" in line.lower() or "Exception" in line or "Failed to" in line: print(line.rstrip()[:400])
