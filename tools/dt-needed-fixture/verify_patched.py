import struct, sys, zipfile

# Verify a dt_needed-injected APK against the pre-injection padded .so:
#   python verify_patched.py <injected.apk> <original-padded.so> [entry]
apk = sys.argv[1]
orig_path = sys.argv[2] if len(sys.argv) > 2 else 'native-padded/libdtprobe-padded.so'
entry = sys.argv[3] if len(sys.argv) > 3 else 'lib/arm64-v8a/libdtprobe.so'

z = zipfile.ZipFile(apk)
libs = [n for n in z.namelist() if n.startswith('lib/')]
print('lib entries:')
for n in libs:
    print('   %-46s %d' % (n, z.getinfo(n).file_size))
data = z.read(entry)
orig = open(orig_path, 'rb').read()
print('injected %s size=%d  original size=%d  sizeUnchanged=%s' % (entry, len(data), len(orig), len(data) == len(orig)))
diff = [i for i in range(min(len(data), len(orig))) if data[i] != orig[i]]
if diff:
    print('changed bytes: min=%d max=%d count=%d' % (min(diff), max(diff), len(diff)))
else:
    print('no byte differences')

d = data
e_shoff = struct.unpack_from('<Q', d, 0x28)[0]
e_shentsize = struct.unpack_from('<H', d, 0x3A)[0]
e_shnum = struct.unpack_from('<H', d, 0x3C)[0]
e_shstrndx = struct.unpack_from('<H', d, 0x3E)[0]
secs = []
for i in range(e_shnum):
    secs.append(list(struct.unpack_from('<IIQQQQIIQQ', d, e_shoff + i*e_shentsize)))
shstr = secs[e_shstrndx][4]
def nm(i):
    raw = d[shstr + secs[i][0]:]
    return raw[:raw.index(b'\x00')].decode()
names = [nm(i) for i in range(e_shnum)]
ids = names.index('.dynstr'); idd = names.index('.dynamic')
blob = d[secs[ids][4]:secs[ids][4] + secs[ids][5]]
print('dynstr size=%d contains libaijsgadget.so=%s' % (len(blob), b'libaijsgadget.so\x00' in blob))
es = secs[idd][9] or 16
n = secs[idd][5] // es
needed = []; nulls = 0
for i in range(n):
    tag, val = struct.unpack_from('<QQ', d, secs[idd][4] + i*es)
    if tag == 1:
        s = blob[val:]
        needed.append(s[:s.index(b'\x00')].decode())
    if tag == 0:
        nulls += 1
print('DT_NEEDED = %s' % needed)
print('DT_NULL entries remaining = %d' % nulls)
print('libaijsgadget present in apk = %s' % any('libaijsgadget.so' in n for n in libs))