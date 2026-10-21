import importlib.util, numpy as np, sys
from datetime import datetime, timezone, timedelta
from math import atan, tan, radians
spec=importlib.util.spec_from_file_location("pv","tools/preview.py"); pv=importlib.util.module_from_spec(spec); spec.loader.exec_module(pv)
tanx=tan(radians(30)/2)*1280/2856; tany=tan(radians(15)); dist=1/np.sin(atan(0.92*tanx))
start=datetime.fromisoformat(sys.argv[1]); minutes=int(sys.argv[2]); mode=sys.argv[3]
for k in range(0,minutes*60,10):
    t=start+timedelta(seconds=k); jd=t.timestamp()/86400+2440587.5
    g=pv.gmst(jd); moon=pv.earth_fixed(pv.moon_eq(jd),g); sun=pv.earth_fixed(pv.sun_eq(jd),g)
    pos,fwd,right,up=pv.orbit_pose(jd,dist)
    if mode=="moon":
        v=moon*pv.moon_distance(jd)-pos; f=v@fwd
        if f>0:
            x=(v@right)/f/tanx; y=(v@up)/f/tany
            if abs(x)<1.0 and abs(y)<1.0 and np.hypot(x,y)>0.0: print(t.strftime("%H:%M:%S"),"moon ndc",round(x,2),round(y,2),"sep-sun deg",round(float(np.degrees(np.arccos(np.clip(sun@moon,-1,1)))),2))
    elif mode=="lon":
        lon=float(np.degrees(np.arctan2(-pos[2],pos[0]))); print(t.strftime("%H:%M:%S"),"subpoint lon",round(lon,1))
