import subprocess, os, time, statistics, random, sys
adb=os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
env=dict(os.environ, MSYS_NO_PATHCONV="1")
def sh(cmd, **kw): return subprocess.run([adb,"shell",cmd],capture_output=True,text=True,env=env,**kw).stdout
variants=[("all on",0),("1 cloud tap",64),("no stars",1),("no clouds+relief",384),("floor (nothing optional)",1023)]
rounds=int(sys.argv[1]) if len(sys.argv)>1 else 3
res={n:[] for n,_ in variants}
order=[]
rnd=random.Random(5)
for r in range(rounds):
    v=variants[:]; rnd.shuffle(v); order+=v
sh("input keyevent KEYCODE_WAKEUP"); sh("input keyevent KEYCODE_HOME")
for name,mask in order:
    sh(f"run-as com.example.earthlive sh -c 'echo {mask} > files/skip_mask'")
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(8)  # let the load and the charger settle
    out=sh("for i in $(seq 70); do cat /sys/class/power_supply/battery/current_now; sleep 0.5; done", timeout=120)
    vals=[int(x)/1000 for x in out.split() if x.lstrip('-').isdigit()]
    m=statistics.mean(vals); res[name].append(m)
    print(f"{name:28s} mean current {m:7.1f} mA  (n={len(vals)}, sd {statistics.pstdev(vals):.0f})", flush=True)
sh("run-as com.example.earthlive rm -f files/skip_mask")
print()
for n,_ in variants: print(f"{n:28s} {statistics.mean(res[n]):7.1f} mA  per-round {[round(x) for x in res[n]]}")
