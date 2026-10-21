import subprocess, os, numpy as np
from PIL import Image
env0=dict(os.environ)
best=[]
for h,m,sec in [(10,0,0),(10,2,0),(10,4,0),(10,6,0),(10,8,0),(10,10,0),(10,12,0),(10,14,0),(10,16,0),(10,18,0),(10,20,0),(10,22,0)]:
    t=f"2026-10-20T{h:02d}:{m:02d}:{sec:02d}Z"
    subprocess.run(["python","-I","tools/preview.py",t,"tools/build/fl_a.png"],capture_output=True,env=env0)
    subprocess.run(["python","-I","tools/preview.py",t,"tools/build/fl_b.png"],capture_output=True,env=dict(env0,SKIP="2048"))
    a=np.asarray(Image.open("tools/build/fl_a.png").convert("RGB"),dtype=np.int16); b=np.asarray(Image.open("tools/build/fl_b.png").convert("RGB"),dtype=np.int16)
    d=np.abs(a-b).max(2); print(t,"flash px >6:",int((d>6).sum()),"max",int(d.max()),flush=True)
