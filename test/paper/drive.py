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
T = '{Slot:3b,id:"minecraft:echo_shard",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"soulbound:tumour":1b}}}}'
W = lambda s: '{Slot:%db,id:"minecraft:potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:water"}}}' % s
cmd("forceload add 0 0")
cmd('setblock 0 -50 0 minecraft:brewing_stand{Items:[%s,%s,%s,{Slot:4b,id:"minecraft:blaze_powder",count:64}]}' % (W(0), W(1), T))
# a control: vanilla awkward brew
cmd('setblock 2 -50 0 minecraft:brewing_stand{Items:[%s,{Slot:3b,id:"minecraft:nether_wart",count:1},{Slot:4b,id:"minecraft:blaze_powder",count:64}]}' % W(0))
time.sleep(4)
rep.append("brewtime custom after 4s: " + str(cmd("data get block 0 -50 0 BrewTime", r"BrewTime|no|has the following", 20)))
rep.append("brewtime vanilla after 4s: " + str(cmd("data get block 2 -50 0 BrewTime", r"BrewTime|no|has the following", 20)))
time.sleep(25)
rep.append("custom items: " + str(cmd("data get block 0 -50 0 Items", r"has the following|Items", 20)))
rep.append("vanilla items: " + str(cmd("data get block 2 -50 0 Items", r"has the following|Items", 20)))
cmd("stop"); p.wait(timeout=300)
open("report.txt", "w").write("\n".join(rep) + "\n")
print("\n".join(rep))
