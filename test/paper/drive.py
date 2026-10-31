import subprocess, re, time, threading, queue
p = subprocess.Popen(["java", "-Xmx3G", "-jar", "server.jar", "nogui"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
q = queue.Queue(); lines = []
def pump():
    for l in p.stdout: lines.append(l); q.put(l)
threading.Thread(target=pump, daemon=True).start()
def wait_for(pat, t=600):
    end = time.time() + t
    while time.time() < end:
        try: l = q.get(timeout=1)
        except queue.Empty: continue
        m = re.search(pat, l)
        if m: return l
    return None
def cmd(c, pat=None, t=60):
    while not q.empty(): q.get_nowait()
    p.stdin.write(c + "\n"); p.stdin.flush()
    return wait_for(pat, t) if pat else None
rep = []
rep.append("done: " + str(wait_for(r"Done \(", 900)))
rep.append("brews line: " + str([l.strip() for l in lines if "Growth potion brews" in l]))
cmd("forceload add 0 0")
for st in (1, 2, 3, 4, 5):
    x = st * 2
    cmd("setblock %d -50 0 minecraft:brewing_stand" % x); time.sleep(0.5)
    rep.append("fill %d: %s" % (st, cmd("moloch gear @brew %d %d -50 0" % (st, x), r"Filled|isn't|rror|nknown|Usage|/moloch", 20)))
time.sleep(4)
for st in (1, 2, 3, 4, 5):
    rep.append("brewtime %d after 4s: %s" % (st, cmd("data get block %d -50 0 BrewTime" % (st * 2), r"has the following|no", 20)))
time.sleep(25)
for st in (1, 2, 3, 4, 5):
    l = cmd("data get block %d -50 0 Items" % (st * 2), r"has the following", 20) or ""
    rep.append("items %d: %s" % (st, l[:900]))
cmd("stop"); p.wait(timeout=300)
open("report.txt", "w").write("\n".join(rep) + "\n")
print("\n".join(rep))
# 1793470893
