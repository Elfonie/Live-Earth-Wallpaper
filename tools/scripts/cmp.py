import subprocess, sys, os, numpy as np
from PIL import Image
scenes={"europe":"2026-10-20T10:06:30Z","night":"2026-10-20T10:11:08Z","pacific":"2026-10-20T09:10:00Z","asia":"2026-10-20T09:27:20Z"}
ref=sys.argv[1]
for n,t in scenes.items():
    subprocess.run(["python","-I","tools/preview.py",t,f"tools/build/cmp_new_{n}.png"],capture_output=True)
    subprocess.run(["python","-I","tools/preview.py",t,f"tools/build/cmp_old_{n}.png"],capture_output=True,env=dict(os.environ,SHADER=ref))
    a=np.asarray(Image.open(f"tools/build/cmp_new_{n}.png").convert("RGB"),dtype=np.int16); b=np.asarray(Image.open(f"tools/build/cmp_old_{n}.png").convert("RGB"),dtype=np.int16)
    d=np.abs(a-b).max(2)
    print(f"{n:8s} max diff {d.max():3d}/255  pixels >2: {(d>2).mean()*100:.4f}%  >8: {(d>8).mean()*100:.4f}%  mean {d.mean():.4f}")
