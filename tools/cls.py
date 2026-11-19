# usage: python tools/cls.py ClassName [...]  -> fields and method signatures of a class from dump/dump.cs (no RVAs)
import sys, re
want = set(sys.argv[1:]); on = False
for line in open('dump/dump.cs', encoding='utf-8', errors='replace'):
    if not line.strip(): continue
    if not line.startswith('\t') and not line.startswith('}') and not line.startswith('{'):
        m = re.search(r'(?:class|struct|enum|interface) ([\w.<>]+)', line)
        on = bool(m and m.group(1) in want)
        if on: print(line.rstrip())
        continue
    if on:
        s = line.strip()
        if not s or s.startswith('// RVA') or s.startswith('[') or s in ('{','}') or s.startswith('// Fields') or s.startswith('// Methods') or s.startswith('// Properties') or s.startswith('|') or s.startswith('/*') or s.startswith('*/'): continue
        if 'get_' in s or 'set_' in s or '.ctor' in s or 'add_' in s or 'remove_' in s: continue
        print('  ' + s)
