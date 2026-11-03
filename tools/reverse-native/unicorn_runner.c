/*
 * unicorn_runner - bounded libunicorn CLI for Android reverse tooling.
 * Native executable (not JNI). Guest bytes are only run by the emulator.
 */
#define _FILE_OFFSET_BITS 64

#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <unicorn/unicorn.h>

#define CODE_BASE 0x01000000ULL
#define STACK_BASE 0x02000000ULL
#define DATA_BASE 0x03000000ULL
#define MAP_SIZE 0x00100000ULL
#define STACK_TOP (STACK_BASE + MAP_SIZE - 16ULL)
#define MAX_CODE 65536U
#define MAX_DATA 4096U
#define MAX_ASSIGN 64

typedef enum { ARCH_ARM64, ARCH_ARM, ARCH_THUMB, ARCH_X86, ARCH_X86_64 } Arch;
typedef enum { STOP_NONE, STOP_RETURNED, STOP_TIMEOUT, STOP_INSN_LIMIT,
               STOP_ERROR, STOP_UNEXPECTED } StopReason;
typedef struct { const char *name; int id; unsigned char width, flags; } RegSpec;
enum { REG_IN = 1, REG_OUT = 2 };
#define RW (REG_IN | REG_OUT)
#define A64(n) {"X" #n, UC_ARM64_REG_X##n, 8, RW}
#define A32(n) {"R" #n, UC_ARM_REG_R##n, 4, RW}
#define X86(n) {#n, UC_X86_REG_##n, 4, RW}
#define X64(n) {#n, UC_X86_REG_##n, 8, RW}

static const RegSpec arm64_regs[] = {
    A64(0),  A64(1),  A64(2),  A64(3),  A64(4),  A64(5),  A64(6),  A64(7),
    A64(8),  A64(9),  A64(10), A64(11), A64(12), A64(13), A64(14), A64(15),
    A64(16), A64(17), A64(18), A64(19), A64(20), A64(21), A64(22), A64(23),
    A64(24), A64(25), A64(26), A64(27), A64(28), A64(29), A64(30),
    {"SP", UC_ARM64_REG_SP, 8, RW}, {"PC", UC_ARM64_REG_PC, 8, REG_OUT},
};
static const RegSpec arm_regs[] = {
    A32(0),  A32(1),  A32(2),  A32(3),  A32(4),  A32(5),  A32(6),  A32(7),
    A32(8),  A32(9),  A32(10), A32(11), A32(12),
    {"SP", UC_ARM_REG_SP, 4, RW}, {"LR", UC_ARM_REG_LR, 4, RW},
    {"R13", UC_ARM_REG_SP, 4, REG_IN}, {"R14", UC_ARM_REG_LR, 4, REG_IN},
    {"R15", UC_ARM_REG_PC, 4, REG_IN}, {"PC", UC_ARM_REG_PC, 4, REG_OUT},
};
static const RegSpec x86_regs[] = {
    X86(EAX), X86(EBX), X86(ECX), X86(EDX), X86(ESI),
    X86(EDI), X86(EBP), X86(ESP), X86(EIP),
};
static const RegSpec x64_regs[] = {
    X64(RAX), X64(RBX), X64(RCX), X64(RDX), X64(RSI), X64(RDI),
    X64(RBP), X64(RSP), X64(RIP), X64(R8), X64(R9), X64(R10),
    X64(R11), X64(R12), X64(R13), X64(R14), X64(R15),
};

typedef struct {
    Arch arch;
    const char *arch_name;
    unsigned char data[MAX_DATA];
    size_t data_len, code_len, reg_count;
    uint64_t timeout_ms, instruction_limit, code_end, entry, instructions;
    StopReason stop;
    uc_err uc_err;
    bool truncated, regs_ok;
    uint64_t regs[64];
    char error[192];
} RunState;

static bool strieq(const char *a, const char *b)
{
    while (*a && *b) {
        unsigned char ca = (unsigned char)*a++, cb = (unsigned char)*b++;
        if (ca >= 'a' && ca <= 'z') ca -= 32;
        if (cb >= 'a' && cb <= 'z') cb -= 32;
        if (ca != cb) return false;
    }
    return *a == '\0' && *b == '\0';
}

static const RegSpec *reg_table(Arch arch, size_t *count)
{
    switch (arch) {
    case ARCH_ARM64: *count = sizeof(arm64_regs)/sizeof(arm64_regs[0]); return arm64_regs;
    case ARCH_ARM:
    case ARCH_THUMB: *count = sizeof(arm_regs)/sizeof(arm_regs[0]); return arm_regs;
    case ARCH_X86: *count = sizeof(x86_regs)/sizeof(x86_regs[0]); return x86_regs;
    default: *count = sizeof(x64_regs)/sizeof(x64_regs[0]); return x64_regs;
    }
}

static const RegSpec *find_input_reg(Arch arch, const char *name)
{
    size_t n, i;
    const RegSpec *t = reg_table(arch, &n);
    for (i = 0; i < n; i++)
        if ((t[i].flags & REG_IN) && strieq(t[i].name, name)) return &t[i];
    return NULL;
}

static uc_err write_reg(uc_engine *uc, int id, unsigned width, uint64_t value)
{
    uint32_t v32 = (uint32_t)value;
    return uc_reg_write(uc, id, width == 4 ? (const void *)&v32 : (const void *)&value);
}

static uc_err read_reg(uc_engine *uc, int id, unsigned width, uint64_t *value)
{
    uint32_t v32 = 0;
    uc_err e = uc_reg_read(uc, id, width == 4 ? (void *)&v32 : (void *)value);
    if (width == 4) *value = v32;
    return e;
}

static int parse_dec(const char *s, uint64_t *out)
{
    uint64_t v = 0;
    if (!s || !*s) return 0;
    for (; *s; s++) {
        unsigned d;
        if (*s < '0' || *s > '9') return 0;
        d = (unsigned)(*s - '0');
        if (v > (UINT64_MAX - d) / 10) return 0;
        v = v * 10 + d;
    }
    *out = v;
    return 1;
}

static int parse_hex(const char *s, uint64_t *out)
{
    uint64_t v = 0;
    unsigned digits = 0;
    if (!s) return 0;
    if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2;
    if (!*s) return 0;
    for (; *s; s++) {
        unsigned d;
        if (*s >= '0' && *s <= '9') d = (unsigned)(*s - '0');
        else if (*s >= 'a' && *s <= 'f') d = (unsigned)(*s - 'a' + 10);
        else if (*s >= 'A' && *s <= 'F') d = (unsigned)(*s - 'A' + 10);
        else return 0;
        if (v > (UINT64_MAX - d) / 16) return 0;
        v = v * 16 + d;
        digits++;
    }
    if (!digits) return 0;
    *out = v;
    return 1;
}

static int read_file_limit(const char *path, size_t limit, unsigned char *buf,
                           size_t *len, char *err, size_t errlen)
{
    struct stat st;
    size_t n, off = 0;
    int fd = open(path, O_RDONLY);
    if (fd < 0) { snprintf(err, errlen, "cannot open %s: %s", path, strerror(errno)); return 0; }
    if (fstat(fd, &st) != 0) {
        snprintf(err, errlen, "cannot stat %s: %s", path, strerror(errno));
        close(fd); return 0;
    }
    if (!S_ISREG(st.st_mode)) {
        snprintf(err, errlen, "%s is not a regular file", path); close(fd); return 0;
    }
    if (st.st_size < 0 || (uint64_t)st.st_size > (uint64_t)limit) {
        snprintf(err, errlen, "%s exceeds the %zu-byte limit", path, limit);
        close(fd); return 0;
    }
    n = (size_t)st.st_size;
    while (off < n) {
        ssize_t r = read(fd, buf + off, n - off);
        if (r < 0) {
            if (errno == EINTR) continue;
            snprintf(err, errlen, "cannot read %s: %s", path, strerror(errno));
            close(fd); return 0;
        }
        if (r == 0) {
            snprintf(err, errlen, "%s changed while reading", path); close(fd); return 0;
        }
        off += (size_t)r;
    }
    for (;;) {
        unsigned char extra;
        ssize_t r = read(fd, &extra, 1);
        if (r < 0 && errno == EINTR) continue;
        if (r < 0) {
            snprintf(err, errlen, "cannot finish reading %s: %s", path, strerror(errno));
            close(fd); return 0;
        }
        if (r > 0) {
            snprintf(err, errlen, "%s changed while reading", path); close(fd); return 0;
        }
        break;
    }
    close(fd);
    *len = n;
    return 1;
}

static const char *stop_name(StopReason s)
{
    switch (s) {
    case STOP_RETURNED: return "returned";
    case STOP_TIMEOUT: return "timeout";
    case STOP_INSN_LIMIT: return "instruction_limit";
    case STOP_ERROR: return "emulator_error";
    case STOP_UNEXPECTED: return "unexpected_exit";
    default: return "not_started";
    }
}

static const char *uc_err_name(uc_err e)
{
    static const char *names[] = {
        "UC_ERR_OK", "UC_ERR_NOMEM", "UC_ERR_ARCH", "UC_ERR_HANDLE",
        "UC_ERR_MODE", "UC_ERR_VERSION", "UC_ERR_READ_UNMAPPED",
        "UC_ERR_WRITE_UNMAPPED", "UC_ERR_FETCH_UNMAPPED", "UC_ERR_HOOK",
        "UC_ERR_INSN_INVALID", "UC_ERR_MAP", "UC_ERR_WRITE_PROT",
        "UC_ERR_READ_PROT", "UC_ERR_FETCH_PROT", "UC_ERR_ARG",
        "UC_ERR_READ_UNALIGNED", "UC_ERR_WRITE_UNALIGNED",
        "UC_ERR_FETCH_UNALIGNED", "UC_ERR_HOOK_EXIST", "UC_ERR_RESOURCE",
        "UC_ERR_EXCEPTION", "UC_ERR_OVERFLOW",
    };
    unsigned i = (unsigned)e;
    return i < sizeof(names)/sizeof(names[0]) ? names[i] : "UC_ERR_UNKNOWN";
}

static void json_str(FILE *out, const char *s)
{
    const unsigned char *p = (const unsigned char *)(s ? s : "");
    fputc('"', out);
    for (; *p; p++) {
        switch (*p) {
        case '"': fputs("\\\"", out); break;
        case '\\': fputs("\\\\", out); break;
        case '\b': fputs("\\b", out); break;
        case '\f': fputs("\\f", out); break;
        case '\n': fputs("\\n", out); break;
        case '\r': fputs("\\r", out); break;
        case '\t': fputs("\\t", out); break;
        default:
            if (*p < 0x20 || *p >= 0x7f) fprintf(out, "\\u%04x", *p);
            else fputc(*p, out);
        }
    }
    fputc('"', out);
}

static void fail_state(RunState *st, uc_err e, const char *what)
{
    st->stop = STOP_ERROR;
    st->uc_err = e;
    st->truncated = false;
    snprintf(st->error, sizeof(st->error), "%s: %s", what, uc_strerror(e));
}

static void code_hook(uc_engine *uc, uint64_t address, uint32_t size, void *user)
{
    RunState *st = (RunState *)user;
    (void)size;
    if (st->stop != STOP_NONE) { uc_emu_stop(uc); return; }
    if (address == st->code_end ||
        (st->arch == ARCH_THUMB && address == (st->code_end | 1ULL))) {
        st->stop = STOP_RETURNED; uc_emu_stop(uc); return;
    }
    if (st->instructions >= st->instruction_limit) {
        st->stop = STOP_INSN_LIMIT; st->truncated = true; uc_emu_stop(uc); return;
    }
    st->instructions++;
}

static void print_json(const RunState *st)
{
    size_t n, i, j = 0;
    const RegSpec *t = reg_table(st->arch, &n);
    bool limited = st->stop == STOP_TIMEOUT || st->stop == STOP_INSN_LIMIT;
    bool ok = (st->stop == STOP_RETURNED || limited) && st->regs_ok;
    const char *limited_by = limited ? (st->stop == STOP_TIMEOUT ? "timeout" : "instruction_limit") : NULL;

    fputs("{\"ok\":", stdout); fputs(ok ? "true" : "false", stdout);
    fputs(",\"architecture\":", stdout); json_str(stdout, st->arch_name);
    fputs(",\"stopReason\":", stdout); json_str(stdout, stop_name(st->stop));
    printf(",\"ucError\":%d,\"ucErrorName\":", (int)st->uc_err);
    json_str(stdout, uc_err_name(st->uc_err));
    fputs(",\"ucErrorMessage\":", stdout); json_str(stdout, uc_strerror(st->uc_err));
    printf(",\"instructions\":%llu,\"instructionLimit\":%llu,\"timeoutMs\":%llu"
           ",\"truncated\":%s,\"limitedBy\":",
           (unsigned long long)st->instructions, (unsigned long long)st->instruction_limit,
           (unsigned long long)st->timeout_ms, limited ? "true" : "false");
    if (limited_by) json_str(stdout, limited_by); else fputs("null", stdout);
    printf(",\"timedOut\":%s,\"instructionLimitReached\":%s,\"registersComplete\":%s"
           ",\"bases\":{\"code\":%llu,\"stack\":%llu,\"data\":%llu,\"mapSize\":%llu"
           ",\"codeSize\":%zu,\"dataSize\":%zu,\"codeEnd\":%llu,\"entry\":%llu}",
           st->stop == STOP_TIMEOUT ? "true" : "false",
           st->stop == STOP_INSN_LIMIT ? "true" : "false",
           st->regs_ok ? "true" : "false",
           (unsigned long long)CODE_BASE, (unsigned long long)STACK_BASE,
           (unsigned long long)DATA_BASE, (unsigned long long)MAP_SIZE,
           st->code_len, st->data_len, (unsigned long long)st->code_end,
           (unsigned long long)st->entry);
    fputs(",\"registers\":{", stdout);
    for (i = 0; i < n; i++) {
        uint64_t v = 0;
        if (!(t[i].flags & REG_OUT)) continue;
        if (j < st->reg_count) v = st->regs[j];
        if (j) fputc(',', stdout);
        json_str(stdout, t[i].name);
        if (t[i].width == 4) printf(":\"0x%08llx\"", (unsigned long long)v);
        else printf(":\"0x%016llx\"", (unsigned long long)v);
        j++;
    }
    fputs("},\"dataHex\":\"", stdout);
    for (i = 0; i < st->data_len; i++) printf("%02x", st->data[i]);
    fputs("\",\"error\":", stdout);
    if (st->error[0]) json_str(stdout, st->error); else fputs("null", stdout);
    fputs("}\n", stdout); fflush(stdout);
}

static void usage(const char *argv0)
{
    fprintf(stderr, "usage: %s <arm64|arm|thumb|x86|x86_64> <code_file> "
            "<data_file_or_-> <timeout_ms 1..1000> <instruction_limit 1..100000> "
            "[REG=HEX ...]\n", argv0);
}

int main(int argc, char **argv)
{
    unsigned char code[MAX_CODE], data[MAX_DATA];
    RunState st;
    struct { const RegSpec *spec; uint64_t value; } assigns[MAX_ASSIGN];
    size_t code_len = 0, data_len = 0, count = 0, out_count = 0;
    uint64_t timeout = 0, limit = 0;
    int assign_count = 0, i, arch_id, mode_id;
    uc_engine *uc = NULL;
    uc_hook hook;
    uc_err e;
    char err[256];

    memset(&st, 0, sizeof(st));
    if (argc < 6) { usage(argv[0]); return 2; }
    if (strcmp(argv[1], "arm64") == 0) {
        st.arch = ARCH_ARM64; st.arch_name = "arm64"; arch_id = UC_ARCH_ARM64; mode_id = UC_MODE_ARM;
    } else if (strcmp(argv[1], "arm") == 0) {
        st.arch = ARCH_ARM; st.arch_name = "arm"; arch_id = UC_ARCH_ARM; mode_id = UC_MODE_ARM;
    } else if (strcmp(argv[1], "thumb") == 0) {
        st.arch = ARCH_THUMB; st.arch_name = "thumb"; arch_id = UC_ARCH_ARM; mode_id = UC_MODE_THUMB;
    } else if (strcmp(argv[1], "x86") == 0) {
        st.arch = ARCH_X86; st.arch_name = "x86"; arch_id = UC_ARCH_X86; mode_id = UC_MODE_32;
    } else if (strcmp(argv[1], "x86_64") == 0) {
        st.arch = ARCH_X86_64; st.arch_name = "x86_64"; arch_id = UC_ARCH_X86; mode_id = UC_MODE_64;
    } else {
        fprintf(stderr, "unicorn_runner: unsupported architecture: %s\n", argv[1]); return 2;
    }
    {
        const RegSpec *t = reg_table(st.arch, &count);
        for (i = 0; i < (int)count; i++) if (t[i].flags & REG_OUT) out_count++;
        st.reg_count = out_count;
    }
    if (!parse_dec(argv[4], &timeout) || timeout < 1 || timeout > 1000) {
        fprintf(stderr, "unicorn_runner: timeout_ms must be an integer in 1..1000\n"); return 2;
    }
    if (!parse_dec(argv[5], &limit) || limit < 1 || limit > 100000) {
        fprintf(stderr, "unicorn_runner: instruction_limit must be an integer in 1..100000\n"); return 2;
    }
    for (i = 6; i < argc; i++) {
        char name[32];
        const char *eq = strchr(argv[i], '=');
        const RegSpec *spec;
        uint64_t value;
        size_t nlen;
        if (!eq || eq == argv[i] || eq[1] == '\0') {
            fprintf(stderr, "unicorn_runner: invalid register assignment: %s\n", argv[i]); return 2;
        }
        nlen = (size_t)(eq - argv[i]);
        if (nlen >= sizeof(name)) {
            fprintf(stderr, "unicorn_runner: register name is too long: %s\n", argv[i]); return 2;
        }
        memcpy(name, argv[i], nlen); name[nlen] = '\0';
        spec = find_input_reg(st.arch, name);
        if (!spec) {
            fprintf(stderr, "unicorn_runner: register %s is not valid for %s\n", name, st.arch_name); return 2;
        }
        if (!parse_hex(eq + 1, &value)) {
            fprintf(stderr, "unicorn_runner: invalid hex value in %s\n", argv[i]); return 2;
        }
        if (spec->width == 4 && value > UINT32_MAX) {
            fprintf(stderr, "unicorn_runner: value for %s exceeds 32 bits\n", spec->name); return 2;
        }
        if (assign_count == MAX_ASSIGN) {
            fprintf(stderr, "unicorn_runner: too many register assignments\n"); return 2;
        }
        assigns[assign_count].spec = spec; assigns[assign_count].value = value; assign_count++;
    }
    if (!read_file_limit(argv[2], MAX_CODE, code, &code_len, err, sizeof(err))) {
        fprintf(stderr, "unicorn_runner: %s\n", err); return 2;
    }
    if (code_len == 0) {
        fprintf(stderr, "unicorn_runner: code_file is empty\n"); return 2;
    }
    if (strcmp(argv[3], "-") != 0 &&
        !read_file_limit(argv[3], MAX_DATA, data, &data_len, err, sizeof(err))) {
        fprintf(stderr, "unicorn_runner: %s\n", err); return 2;
    }
    st.data_len = data_len; memcpy(st.data, data, data_len);
    st.code_len = code_len; st.timeout_ms = timeout; st.instruction_limit = limit;
    st.code_end = CODE_BASE + (uint64_t)code_len; st.entry = CODE_BASE;

#define CHECK(x, msg) do { if ((e = (x)) != UC_ERR_OK) { fail_state(&st, e, msg); goto finish; } } while (0)
    CHECK(uc_open((uc_arch)arch_id, (uc_mode)mode_id, &uc), "uc_open");
    CHECK(uc_mem_map(uc, CODE_BASE, MAP_SIZE, UC_PROT_ALL), "map code");
    CHECK(uc_mem_map(uc, STACK_BASE, MAP_SIZE, UC_PROT_READ | UC_PROT_WRITE), "map stack");
    CHECK(uc_mem_map(uc, DATA_BASE, MAP_SIZE, UC_PROT_READ | UC_PROT_WRITE), "map data");
    CHECK(uc_mem_write(uc, CODE_BASE, code, code_len), "write code");
    if (data_len) CHECK(uc_mem_write(uc, DATA_BASE, data, data_len), "write data");
    CHECK(uc_hook_add(uc, &hook, UC_HOOK_CODE, (void *)code_hook, &st, 1, 0), "add code hook");
    if (st.arch == ARCH_ARM64) {
        CHECK(write_reg(uc, UC_ARM64_REG_SP, 8, STACK_TOP), "set SP");
        CHECK(write_reg(uc, UC_ARM64_REG_LR, 8, st.code_end), "set LR");
        CHECK(write_reg(uc, UC_ARM64_REG_PC, 8, CODE_BASE), "set PC");
    } else if (st.arch == ARCH_ARM || st.arch == ARCH_THUMB) {
        uint64_t lr = st.arch == ARCH_THUMB ? (st.code_end | 1ULL) : st.code_end;
        CHECK(write_reg(uc, UC_ARM_REG_SP, 4, STACK_TOP), "set SP");
        CHECK(write_reg(uc, UC_ARM_REG_LR, 4, lr), "set LR");
        CHECK(write_reg(uc, UC_ARM_REG_PC, 4, CODE_BASE), "set PC");
    } else if (st.arch == ARCH_X86) {
        CHECK(write_reg(uc, UC_X86_REG_ESP, 4, STACK_TOP), "set ESP");
        CHECK(write_reg(uc, UC_X86_REG_EIP, 4, CODE_BASE), "set EIP");
    } else {
        CHECK(write_reg(uc, UC_X86_REG_RSP, 8, STACK_TOP), "set RSP");
        CHECK(write_reg(uc, UC_X86_REG_RIP, 8, CODE_BASE), "set RIP");
    }
    for (i = 0; i < assign_count; i++)
        CHECK(write_reg(uc, assigns[i].spec->id, assigns[i].spec->width, assigns[i].value), "set register");
    if (st.arch == ARCH_X86 || st.arch == ARCH_X86_64) {
        unsigned ws = st.arch == ARCH_X86 ? 4 : 8;
        unsigned id = st.arch == ARCH_X86 ? UC_X86_REG_ESP : UC_X86_REG_RSP;
        unsigned char ret[8];
        uint64_t sp;
        CHECK(read_reg(uc, (int)id, ws, &sp), "read stack pointer");
        if (sp < ws) { fail_state(&st, UC_ERR_ARG, "x86 stack pointer underflow"); goto finish; }
        for (i = 0; i < (int)ws; i++) ret[i] = (unsigned char)(st.code_end >> (8 * i));
        CHECK(uc_mem_write(uc, sp - ws, ret, ws), "push return address");
        CHECK(write_reg(uc, (int)id, ws, sp - ws), "set stack pointer");
    }
    {
        int pc_id, pc_width;
        if (st.arch == ARCH_ARM64) { pc_id = UC_ARM64_REG_PC; pc_width = 8; }
        else if (st.arch == ARCH_ARM || st.arch == ARCH_THUMB) { pc_id = UC_ARM_REG_PC; pc_width = 4; }
        else if (st.arch == ARCH_X86) { pc_id = UC_X86_REG_EIP; pc_width = 4; }
        else { pc_id = UC_X86_REG_RIP; pc_width = 8; }
        CHECK(read_reg(uc, pc_id, (unsigned)pc_width, &st.entry), "read entry point");
        /* Unicorn selects ARM/Thumb execution from the entry address low bit. */
        if (st.arch == ARCH_THUMB) st.entry |= 1ULL;
    }
    st.stop = STOP_NONE;
    e = uc_emu_start(uc, st.entry, 0, timeout * 1000ULL, 0);
    if (e != UC_ERR_OK) {
        fail_state(&st, e, "emulation");
    } else if (st.stop == STOP_NONE) {
        size_t timed_out = 0;
        e = uc_query(uc, UC_QUERY_TIMEOUT, &timed_out);
        if (e != UC_ERR_OK) fail_state(&st, e, "query timeout");
        else if (timed_out) { st.stop = STOP_TIMEOUT; st.truncated = true; }
        else { st.stop = STOP_UNEXPECTED; snprintf(st.error, sizeof(st.error), "emulation ended without a known stop reason"); }
    }
    st.regs_ok = true;
    {
        const RegSpec *t = reg_table(st.arch, &count);
        size_t oi = 0;
        for (i = 0; i < (int)out_count; i++) st.regs[i] = 0;
        for (i = 0; i < (int)count; i++) {
            if (!(t[i].flags & REG_OUT)) continue;
            if ((e = read_reg(uc, t[i].id, t[i].width, &st.regs[oi])) != UC_ERR_OK) {
                st.regs_ok = false; st.stop = STOP_ERROR; st.uc_err = e;
                snprintf(st.error, sizeof(st.error), "read register %s: %s", t[i].name, uc_strerror(e));
                break;
            }
            oi++;
        }
        st.reg_count = oi;
    }
finish:
    if (uc) uc_close(uc);
    print_json(&st);
    return st.stop == STOP_RETURNED || st.stop == STOP_TIMEOUT ||
           st.stop == STOP_INSN_LIMIT ? 0 : 1;
}
