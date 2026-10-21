import importlib.util, numpy as np
from datetime import datetime, timezone, timedelta
from math import atan, tan, radians
spec=importlib.util.spec_from_file_location("pv","tools/preview.py"); pv=importlib.util.module_from_spec(spec); spec.loader.exec_module(pv)
tanx=tan(radians(30)/2)*1280/2856; tany=tan(radians(15)); dist=1/np.sin(atan(0.92*tanx))
import sys
day=sys.argv[1]
t0=datetime.fromisoformat(day+"T00:00:00+00:00")
for k in range(0,86400,5):
    t=t0+timedelta(seconds=k); jd=t.timestamp()/86400+2440587.5
    g=pv.gmst(jd); moon=pv.earth_fixed(pv.moon_eq(jd),g)*pv.moon_distance(jd)
    pos,fwd,right,up=pv.orbit_pose(jd,dist)
    v=moon-pos; f=v@fwd
    if f<=0: continue
    x=(v@right)/f/tanx; y=(v@up)/f/tany
    if abs(x)<0.8 and 0.5<abs(y)<0.95: print(t.strftime("%H:%M:%S"), "moon ndc", round(x,2), round(y,2))
