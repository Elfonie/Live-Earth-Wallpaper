import importlib.util, numpy as np
from datetime import datetime, timezone, timedelta
from math import atan, tan, radians
spec=importlib.util.spec_from_file_location("pv","tools/preview.py"); pv=importlib.util.module_from_spec(spec); spec.loader.exec_module(pv)
tanx=tan(radians(30)/2)*1280/2856; tany=tan(radians(15)); dist=1/np.sin(atan(0.92*tanx))
t0=datetime(2026,10,20,10,0,0,tzinfo=timezone.utc)
for k in range(0,720,5):
    t=t0+timedelta(seconds=k); jd=t.timestamp()/86400+2440587.5
    g=pv.gmst(jd); sun=pv.earth_fixed(pv.sun_eq(jd),g)
    pos,fwd,right,up=pv.orbit_pose(jd,dist)
    f=sun@fwd
    if f<=0: continue
    x=(sun@right)/f/tanx; y=(sun@up)/f/tany
    if abs(x)<1.3 and abs(y)<1.3: print(k, t.strftime("%H:%M:%S"), "sun ndc", round(x,2), round(y,2))
