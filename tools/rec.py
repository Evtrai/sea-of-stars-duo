import subprocess,json,time,os,sys
A=[os.environ.get("ADB","adb")]   # set ANDROID_SERIAL to pick a device
D="/sdcard/Android/data/com.evtrai.sosduo/files"
out=sys.argv[1]; last=None; n=0; t0=time.time()
env=dict(os.environ,MSYS_NO_PATHCONV="1")
def sh(*a,**k): return subprocess.run(A+list(a),capture_output=True,env=env,**k)
while time.time()-t0<900 and n<70:
    sh("shell",f"printf 'java:snap\n' > {D}/duo_cmd.txt"); time.sleep(0.9)
    r=sh("exec-out","cat",f"{D}/duo_snap.json")
    try: j=json.loads(r.stdout.decode('utf-8','replace'))
    except Exception: time.sleep(2); continue
    m=j.get('menu') or {}; bx=j.get('bx') or {}
    key=json.dumps([j.get('state'),m.get('kind'),m.get('actor'),m.get('sel'),bx.get('st'),bx.get('tgt'),bx.get('tgtId'),bx.get('ko'),bx.get('fieldMana'),j.get('combo',0)//1000,
        [(e.get('turns'),e.get('locks'),e.get('dead')) for e in j.get('enemies',[])],[(p.get('id'),p.get('mana')) for p in j.get('party',[])]])
    if key!=last:
        last=key; n+=1
        for nm,d in (("t","4630946441858561667"),("b","4630946482288158084")):
            p=sh("exec-out","screencap","-p","-d",d); open(f"{out}/{n:03d}_{nm}.png","wb").write(p.stdout)
        json.dump(j,open(f"{out}/{n:03d}.json","w"))
        print(n,key[:200],flush=True)
    time.sleep(1.2)
print("done",n)
