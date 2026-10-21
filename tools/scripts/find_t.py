import sys; sys.path.insert(0, r"tools"); 
import importlib.util, numpy as np
from datetime import datetime, timezone, timedelta
spec=importlib.util.spec_from_file_location("pv","tools/preview.py"); pv=importlib.util.module_from_spec(spec); spec.loader.exec_module(pv)
t0=datetime(2026,10,20,10,0,0,tzinfo=timezone.utc)
for k in range(0,720,15):
    t=t0+timedelta(seconds=k); jd=t.timestamp()/86400+2440587.5
    pos=pv.orbit_pose(jd,9.0)[0]; print(k, round(float(np.degrees(np.arctan2(-pos[2],pos[0]))),1), end=" | ")
