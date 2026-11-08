import sys, struct

def parse(path):
    d = open(path,'rb').read()
    if d[:4] != b'\x7fELF': raise SystemExit('not ELF')
    is64 = d[4] == 2
    if not is64: raise SystemExit('only ELF64 supported here')
    e_phoff = struct.unpack_from('<Q', d, 0x20)[0]
    e_shoff = struct.unpack_from('<Q', d, 0x28)[0]
    e_phentsize = struct.unpack_from('<H', d, 0x36)[0]
    e_phnum = struct.unpack_from('<H', d, 0x38)[0]
    e_shentsize = struct.unpack_from('<H', d, 0x3A)[0]
    e_shnum = struct.unpack_from('<H', d, 0x3C)[0]
    e_shstrndx = struct.unpack_from('<H', d, 0x3E)[0]
    secs = []
    for i in range(e_shnum):
        o = e_shoff + i*e_shentsize
        name, typ, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from('<IIQQQQIIQQ', d, o)
        secs.append(dict(name=name,type=typ,addr=addr,offset=offset,size=size,link=link,entsize=entsize))
    shstr = secs[e_shstrndx]
    for s in secs:
        raw = d[shstr['offset']+s['name']:]
        s['n'] = raw[:raw.index(b'\x00')].decode()
    segs = []
    for i in range(e_phnum):
        o = e_phoff + i*e_phentsize
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from('<IIQQQQQQ', d, o)
        segs.append(dict(type=p_type,offset=p_offset,filesz=p_filesz,memsz=p_memsz,vaddr=p_vaddr,align=p_align))
    return d, secs, segs

def report(path):
    d, secs, segs = parse(path)
    print('file=%s size=%d' % (path, len(d)))
    dynstr = next((s for s in secs if s['n']=='.dynstr'), None)
    dynamic = next((s for s in secs if s['n']=='.dynamic'), None)
    print('has .dynstr=%s .dynamic=%s' % (bool(dynstr), bool(dynamic)))
    if dynstr:
        blob = d[dynstr['offset']:dynstr['offset']+dynstr['size']]
        last = -1
        for i,b in enumerate(blob):
            if b != 0: last = i
        free_start = 0 if last < 0 else last + 2
        have = max(0, len(blob) - free_start)
        print('dynstr size=%d lastNonZero=%d freeStart=%d have=%d  (need 17)' % (len(blob), last, free_start, have))
        print('  contains libaijsgadget.so = %s' % (b'libaijsgadget.so\x00' in blob))
    if dynamic:
        es = dynamic['entsize'] or 16
        n = dynamic['size']//es
        nulls = []
        for i in range(n):
            tag, val = struct.unpack_from('<QQ', d, dynamic['offset']+i*es)
            if tag == 0: nulls.append(i)
        pairs = [i for i in nulls if i+1 < n and struct.unpack_from('<Q', d, dynamic['offset']+(i+1)*es)[0]==0]
        print('dynamic entries=%d DT_NULL indices=%s spare_slots=%s' % (n, nulls, pairs))

for p in sys.argv[1:]:
    report(p)