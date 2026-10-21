import importlib.util, numpy as np, subprocess, time, os
adb=os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
spec=importlib.util.spec_from_file_location("pv","tools/preview.py"); pv=importlib.util.module_from_spec(spec); spec.loader.exec_module(pv)
def lon(ts):
    pos=pv.orbit_pose(ts/86400+2440587.5,9.0)[0]; return float(np.degrees(np.arctan2(-pos[2],pos[0])))
dev=int(subprocess.run([adb,"shell","date","+%s"],capture_output=True,text=True).stdout)
host=time.time(); off=dev-host
t=time.time()+off
# wait until lon in [37,40]
while not (36<lon(time.time()+off)<41): time.sleep(2)
for i in range(3):
    subprocess.run(f'"{adb}" exec-out screencap -p > tools/build/dev_af{i}.png',shell=True); time.sleep(6)
print("done", lon(time.time()+off))
