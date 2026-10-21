import subprocess, os, numpy as np
from PIL import Image
best=[]
for hh in range(0,24,3):
    for mm in (2,9,16):
        t=f"2026-10-20T{hh:02d}:{mm:02d}:00+00:00"
        subprocess.run(["python","-I","tools/preview.py",t,"tools/build/sa.png"],capture_output=True)
        subprocess.run(["python","-I","tools/preview.py",t,"tools/build/sb.png"],capture_output=True,env=dict(os.environ,SKIP="16"))
        a=np.asarray(Image.open("tools/build/sa.png").convert("RGB"),dtype=np.int16); b=np.asarray(Image.open("tools/build/sb.png").convert("RGB"),dtype=np.int16)
        d=np.abs(a-b).max(2)
        top=int((d[:1428]>8).sum()); bottom=int((d[1428:]>8).sum())
        print(t,"aurora px north half",top,"south half",bottom,flush=True)
        best.append((bottom,t))
print(sorted(best,reverse=True)[:3])
