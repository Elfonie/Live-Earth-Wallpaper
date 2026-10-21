import subprocess, os, numpy as np
from PIL import Image
base=dict(os.environ)
res=[]
for hh in range(0,24,2):
    for mm in (3,15):
        t=f"2026-06-21T{hh:02d}:{mm:02d}:00.5+00:00"
        subprocess.run(["python","-I","tools/preview.py",t,"tools/build/nl_a.png"],capture_output=True,env=dict(base,SHADER="tools/build/earth_nlc_test.frag"))
        subprocess.run(["python","-I","tools/preview.py",t,"tools/build/nl_b.png"],capture_output=True,env=dict(base,SHADER="tools/build/earth_nlc_test.frag",SKIP="1024"))
        a=np.asarray(Image.open("tools/build/nl_a.png").convert("RGB"),dtype=np.int16); b=np.asarray(Image.open("tools/build/nl_b.png").convert("RGB"),dtype=np.int16)
        d=np.abs(a-b).max(2); c=int((d>6).sum()); res.append((c,t)); print(t,c,flush=True)
print(sorted(res,reverse=True)[:3])
