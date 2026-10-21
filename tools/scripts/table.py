import re,sys
rows=[]
for l in open(sys.argv[1],encoding="utf-8"):
    m=re.search(r"PROFILE \| (.+?)\s+\| (.+?)\s+\|\s+([\d,]+) ms \|\s+([+-][\d,]+) ms",l)
    if m: rows.append((m.group(1),m.group(2),float(m.group(3).replace(",",".")),float(m.group(4).replace(",","."))))
scenes=[]; [scenes.append(r[0]) for r in rows if r[0] not in scenes]
variants=[]; [variants.append(r[1]) for r in rows if r[1] not in variants]
print("%-32s"%"variant: ms (delta)", *["%20s"%s for s in scenes])
for v in variants:
    print("%-32s"%v, *["%20s"%("%.1f (%+.1f)"%([x for x in rows if x[0]==s and x[1]==v][0][2:])) for s in scenes])
