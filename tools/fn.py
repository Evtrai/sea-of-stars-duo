# usage: python tools/fn.py "<text that identifies the method line in dump.cs>" [...]
# Disassembles the method from extract/libil2cpp.so (RVA = file offset + 0x4000) with call targets named.
# Runtime-helper noise (class init checks) is dropped. Needs: pip install capstone
import sys, re, bisect, capstone
data = open('extract/libil2cpp.so','rb').read()
meths=[]; cur=None; rva=None
for line in open('dump/dump.cs', encoding='utf-8', errors='replace'):
    if not line.strip(): continue
    if not line.startswith('\t'):
        m = re.search(r'(?:class|struct|interface) ([\w.<>]+)', line)
        if m: cur = m.group(1)
    m = re.search(r'RVA: 0x([0-9A-F]+) ', line)
    if m and line.strip().startswith('// RVA'): rva = int(m.group(1),16); continue
    if rva and not line.strip().startswith('['):
        meths.append((rva, cur, line.strip())); rva=None
meths.sort(); keys=[m[0] for m in meths]
md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
NOISE = {0x2119734, 0x20faeb8}
for pat in sys.argv[1:]:
    hits = [m for m in meths if pat in (m[1] + ' :: ' + m[2])]
    if not hits: print('?? no match', pat); continue
    s = hits[0][0]; e = keys[bisect.bisect_right(keys, s)]
    print('==', hits[0][1], '::', hits[0][2], hex(s))
    for i in md.disasm(data[s-0x4000:e-0x4000], s):
        t = ''
        if i.mnemonic in ('bl','b') and i.op_str.startswith('#'):
            a = int(i.op_str[1:],16)
            if a in NOISE: continue
            j = bisect.bisect_right(keys,a)-1
            if keys[j]==a: t = '   ; ' + meths[j][1] + '.' + re.sub(r'\{ \}','',meths[j][2])[:110]
            elif s <= a < e: t = ''
            else: t = '   ; sub_%x' % a
        if i.mnemonic in ('stp','ldp') and 'sp' in i.op_str: continue
        if i.mnemonic == 'ldrb' and '#0x135' in i.op_str: continue
        if i.mnemonic == 'tbnz' and t == '' and False: continue
        print('%x  %-5s %s%s' % (i.address, i.mnemonic, i.op_str, t))
