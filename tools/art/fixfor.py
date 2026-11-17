import re,sys
for p in sys.argv[1:]:
    s=open(p).read()
    s2=re.sub(r'for \(final int (\w+) = ([^;]+); \1 ([^;]+); \1\+\+\) \{', lambda m: f'for (int {m.group(1)}_ = {m.group(2)}; {m.group(1)}_ {m.group(3)}; {m.group(1)}_++) {{ final int {m.group(1)} = {m.group(1)}_;', s)
    if s2!=s: print(p, 'fixed for-loops'); open(p,'w').write(s2)
