import subprocess, os, re, time, statistics
adb=os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"); env=dict(os.environ,MSYS_NO_PATHCONV="1")
def sh(c,**k): return subprocess.run([adb,"shell",c],capture_output=True,text=True,env=env,**k).stdout
def install(): subprocess.run([adb,"install","-r","app/build/outputs/apk/debug/app-debug.apk"],capture_output=True,env=env)
modes={"sprites":[],"pixel stars (HDR)":["no_sprites"],"direct (no HDR)":["no_hdr"]}
res={m:[] for m in modes}
for rnd in range(3):
    for m,files in modes.items():
        sh("run-as com.example.earthlive rm -f files/no_sprites files/no_hdr files/fixed_time files/skip_mask files/profile.flag")
        for f in files: sh(f"run-as com.example.earthlive sh -c 'echo 1 > files/{f}'")
        install(); sh("input keyevent KEYCODE_WAKEUP"); sh("input keyevent KEYCODE_HOME"); time.sleep(10)
        sh("logcat -c"); time.sleep(30)
        out=sh("logcat -d -s EarthWallpaper:V")
        ms=sorted(float(x.replace(',','.')) for x in re.findall(r"frame ([\d,.]+) ms",out))
        res[m].append((statistics.median(ms), ms[int(len(ms)*0.95)], len(ms)))
        print(m, "median %.1f p95 %.1f n %d"%res[m][-1], flush=True)
print()
for m in modes: print("%-20s median %.1f ms (rounds %s)  p95 %.1f"%(m, statistics.mean(r[0] for r in res[m]), [round(r[0],1) for r in res[m]], statistics.mean(r[1] for r in res[m])))
