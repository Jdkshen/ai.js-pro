import struct, sys, os

SRC = sys.argv[1] if len(sys.argv)>1 else 'native/libdtprobe.so'
DST = sys.argv[2] if len(sys.argv)>2 else 'native-padded/libdtprobe-padded.so'
PAD_DYNSTR = 32   # extra NULs appended to dynstr (need >= 17 free at tail)

d = bytearray(open(SRC,'rb').read())
assert d[:4] == b'\x7fELF' and d[4] == 2, 'need ELF64'

e_phoff = struct.unpack_from('<Q', d, 0x20)[0]
e_shoff = struct.unpack_from('<Q', d, 0x28)[0]
e_phentsize = struct.unpack_from('<H', d, 0x36)[0]
e_phnum = struct.unpack_from('<H', d, 0x38)[0]
e_shentsize = struct.unpack_from('<H', d, 0x3A)[0]
e_shnum = struct.unpack_from('<H', d, 0x3C)[0]
e_shstrndx = struct.unpack_from('<H', d, 0x3E)[0]

PH = '<IIQQQQQQ'
ph = []
for i in range(e_phnum):
    o = e_phoff + i*e_phentsize
    ph.append(list(struct.unpack_from(PH, d, o)))

SH = '<IIQQQQIIQQ'
sh = []
for i in range(e_shnum):
    o = e_shoff + i*e_shentsize
    sh.append(list(struct.unpack_from(SH, d, o)))

shstr_off = sh[e_shstrndx][4]
def secname(i):
    raw = d[shstr_off + sh[i][0]:]
    return raw[:raw.index(b'\x00')].decode()
names = [secname(i) for i in range(e_shnum)]
i_dynstr = names.index('.dynstr')
i_dynamic = names.index('.dynamic')

def sec(i):  # (offset, size, addr)
    return sh[i][4], sh[i][5], sh[i][3]

ds_off, ds_size, ds_addr = sec(i_dynstr)
dy_off, dy_size, dy_addr = sec(i_dynamic)
old_dynstr = bytes(d[ds_off:ds_off+ds_size])
old_dynamic = bytes(d[dy_off:dy_off+dy_size])
print('dynstr off=%d size=%d addr=0x%x' % (ds_off, ds_size, ds_addr))
print('dynamic off=%d size=%d addr=0x%x entries=%d' % (dy_off, dy_size, dy_addr, dy_size//16))

# PT_LOAD that contains the dynamic section (RW data segment)
base_i = None
for i,p in enumerate(ph):
    if p[0] == 1 and p[2] <= dy_off and dy_off + dy_size <= p[2] + p[5]:
        base_i = i
if base_i is None: raise SystemExit('no PT_LOAD containing .dynamic')
B = ph[base_i]
print('base PT_LOAD[%d] off=%d vaddr=0x%x filesz=%d memsz=%d align=0x%x' % (base_i, B[2], B[3], B[5], B[6], B[7]))

def vaddr_for(off):
    return B[3] + (off - B[2])

append = (len(d) + 7) & ~7
new_dynstr = old_dynstr + b'\x00' * PAD_DYNSTR
# dynamic copy + two spare zero entries (keeps a DT_NULL terminator plus a spare slot)
new_dynamic = bytearray(old_dynamic + b'\x00' * 32)

off_dynstr_new = append
off_dynamic_new = append + len(new_dynstr)
v_dynstr_new = vaddr_for(off_dynstr_new)
v_dynamic_new = vaddr_for(off_dynamic_new)

# patch DT_STRTAB(5) / DT_STRSZ(10) inside the new dynamic copy
for i in range(len(new_dynamic)//16):
    tag, val = struct.unpack_from('<QQ', new_dynamic, i*16)
    if tag == 5:
        struct.pack_into('<QQ', new_dynamic, i*16, 5, v_dynstr_new)
    elif tag == 10:
        struct.pack_into('<QQ', new_dynamic, i*16, 10, len(new_dynstr))

d.extend(b'\x00' * (append - len(d)))
d.extend(new_dynstr)
d.extend(bytes(new_dynamic))
end_new = len(d)

# PT_DYNAMIC -> new copy
dyn_i = None
for i,p in enumerate(ph):
    if p[0] == 2:
        dyn_i = i
if dyn_i is None: raise SystemExit('no PT_DYNAMIC')
struct.pack_into(PH, d, e_phoff + dyn_i*e_phentsize, 2, ph[dyn_i][1], off_dynamic_new, v_dynamic_new, v_dynamic_new, len(new_dynamic), len(new_dynamic), ph[dyn_i][7])

# extend the containing PT_LOAD (and GNU_RELRO, if it covered the old dynamic) to map the new region
B[5] = end_new - B[2]
B[6] = max(B[6], end_new - B[2])
struct.pack_into(PH, d, e_phoff + base_i*e_phentsize, *B)
for i,p in enumerate(ph):
    if p[0] == 0x6474e552:  # PT_GNU_RELRO
        new_mem = max(p[6], end_new - B[2] + B[3] - p[3]) if p[3] <= B[3] else p[6]
        struct.pack_into(PH, d, e_phoff + i*e_phentsize, p[0], p[1], p[2], p[3], p[4], max(p[5], end_new - p[2]) if p[2] == B[2] else p[5], new_mem, p[7])

# section headers: point .dynstr / .dynamic at the new copies
sh[i_dynstr][4], sh[i_dynstr][5], sh[i_dynstr][3] = off_dynstr_new, len(new_dynstr), v_dynstr_new
sh[i_dynamic][4], sh[i_dynamic][5], sh[i_dynamic][3] = off_dynamic_new, len(new_dynamic), v_dynamic_new
struct.pack_into(SH, d, e_shoff + i_dynstr*e_shentsize, *sh[i_dynstr])
struct.pack_into(SH, d, e_shoff + i_dynamic*e_shentsize, *sh[i_dynamic])

os.makedirs(os.path.dirname(DST), exist_ok=True)
open(DST,'wb').write(bytes(d))
print('wrote %s  size %d -> %d' % (DST, len(old_dynstr) and 0 or 0, len(d)))
print('new dynstr off=%d vaddr=0x%x size=%d' % (off_dynstr_new, v_dynstr_new, len(new_dynstr)))
print('new dynamic off=%d vaddr=0x%x size=%d entries=%d' % (off_dynamic_new, v_dynamic_new, len(new_dynamic), len(new_dynamic)//16))