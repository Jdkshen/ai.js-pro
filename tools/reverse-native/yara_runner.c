/*
 * yara_runner - bounded libyara CLI for Android reverse tooling.
 *
 * This is a native executable, not JNI.  It compiles rules from memory, has
 * no include-file callback, and scans a caller-provided file from memory.
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

#include <yara.h>

#define RULES_LIMIT (1024U * 1024U)
#define TARGET_LIMIT (64U * 1024U * 1024U)
#define MAX_RULES 200
#define MAX_OFFSETS 100

typedef struct {
    const char *string;
    int64_t offset;
    int32_t length;
} MatchOut;

typedef struct {
    const YR_RULE *rule;
    MatchOut matches[MAX_OFFSETS];
    size_t match_count;
    bool matches_truncated;
} RuleOut;

typedef struct {
    RuleOut rules[MAX_RULES];
    size_t rule_count;
    bool rules_truncated;
    bool matches_truncated;
} ScanOut;

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

static const char *yara_error_name(int code)
{
    switch (code) {
    case ERROR_SUCCESS: return "ERROR_SUCCESS";
    case ERROR_INSUFFICIENT_MEMORY: return "ERROR_INSUFFICIENT_MEMORY";
    case ERROR_SCAN_TIMEOUT: return "ERROR_SCAN_TIMEOUT";
    case ERROR_CALLBACK_ERROR: return "ERROR_CALLBACK_ERROR";
    case ERROR_TOO_MANY_MATCHES: return "ERROR_TOO_MANY_MATCHES";
    case ERROR_INTERNAL_FATAL_ERROR: return "ERROR_INTERNAL_FATAL_ERROR";
    default: return "ERROR_OTHER";
    }
}

static const char *yara_error_message(int code)
{
    switch (code) {
    case ERROR_SCAN_TIMEOUT: return "scan timed out";
    case ERROR_CALLBACK_ERROR: return "scan callback failed";
    case ERROR_TOO_MANY_MATCHES: return "too many matches";
    case ERROR_INSUFFICIENT_MEMORY: return "out of memory";
    case ERROR_INTERNAL_FATAL_ERROR: return "internal YARA error";
    default: return "YARA scan failed";
    }
}

static int parse_int(const char *s, int min, int max, int *out)
{
    long long v = 0;
    if (!s || !*s) return 0;
    for (; *s; s++) {
        if (*s < '0' || *s > '9') return 0;
        v = v * 10 + (*s - '0');
        if (v > max) return 0;
    }
    if (v < min) return 0;
    *out = (int)v;
    return 1;
}

static int read_file(const char *path, size_t limit, unsigned char **out,
                     size_t *out_len, char *err, size_t errlen)
{
    struct stat st;
    unsigned char *buf;
    size_t size, off = 0;
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        snprintf(err, errlen, "cannot open %s: %s", path, strerror(errno));
        return 0;
    }
    if (fstat(fd, &st) != 0) {
        snprintf(err, errlen, "cannot stat %s: %s", path, strerror(errno));
        close(fd);
        return 0;
    }
    if (!S_ISREG(st.st_mode)) {
        snprintf(err, errlen, "%s is not a regular file", path);
        close(fd);
        return 0;
    }
    if (st.st_size < 0 || (uint64_t)st.st_size > (uint64_t)limit) {
        snprintf(err, errlen, "%s exceeds the %zu-byte limit", path, limit);
        close(fd);
        return 0;
    }
    size = (size_t)st.st_size;
    buf = (unsigned char *)malloc(size ? size : 1);
    if (!buf) {
        snprintf(err, errlen, "out of memory reading %s", path);
        close(fd);
        return 0;
    }
    while (off < size) {
        ssize_t r = read(fd, buf + off, size - off);
        if (r < 0) {
            if (errno == EINTR) continue;
            snprintf(err, errlen, "cannot read %s: %s", path, strerror(errno));
            free(buf);
            close(fd);
            return 0;
        }
        if (r == 0) {
            snprintf(err, errlen, "%s changed while reading", path);
            free(buf);
            close(fd);
            return 0;
        }
        off += (size_t)r;
    }
    for (;;) {
        unsigned char extra;
        ssize_t r = read(fd, &extra, 1);
        if (r < 0 && errno == EINTR) continue;
        if (r < 0) {
            snprintf(err, errlen, "cannot finish reading %s: %s", path, strerror(errno));
            free(buf);
            close(fd);
            return 0;
        }
        if (r > 0) {
            snprintf(err, errlen, "%s changed while reading", path);
            free(buf);
            close(fd);
            return 0;
        }
        break;
    }
    close(fd);
    *out = buf;
    *out_len = size;
    return 1;
}

typedef struct {
    const char *path;
} CompileCtx;

static void compiler_cb(int level, const char *file, int line,
                        const YR_RULE *rule, const char *message, void *user)
{
    CompileCtx *ctx = (CompileCtx *)user;
    fprintf(stderr, "yara_runner: %s: %s:%d: %s\n",
            level == YARA_ERROR_LEVEL_ERROR ? "error" : "warning",
            file ? file : ctx->path, line, message ? message : "");
    if (rule && rule->identifier)
        fprintf(stderr, "yara_runner:   in rule %s\n", rule->identifier);
}

static int scan_cb(YR_SCAN_CONTEXT *context, int message, void *message_data,
                   void *user_data)
{
    ScanOut *out = (ScanOut *)user_data;
    YR_STRING *string;
    RuleOut *rule_out;
    bool full = false;

    if (message == CALLBACK_MSG_TOO_MANY_MATCHES) {
        out->matches_truncated = true;
        return CALLBACK_CONTINUE;
    }
    if (message != CALLBACK_MSG_RULE_MATCHING)
        return CALLBACK_CONTINUE;
    if (out->rule_count >= MAX_RULES) {
        out->rules_truncated = true;
        return CALLBACK_ABORT;
    }

    rule_out = &out->rules[out->rule_count];
    rule_out->rule = (const YR_RULE *)message_data;
    yr_rule_strings_foreach(rule_out->rule, string) {
        YR_MATCH *match;
        yr_string_matches_foreach(context, string, match) {
            if (rule_out->match_count >= MAX_OFFSETS) {
                rule_out->matches_truncated = true;
                out->matches_truncated = true;
                full = true;
                break;
            }
            rule_out->matches[rule_out->match_count].string = string->identifier;
            rule_out->matches[rule_out->match_count].offset = match->base + match->offset;
            rule_out->matches[rule_out->match_count].length = match->match_length;
            rule_out->match_count++;
        }
        if (full) break;
    }
    out->rule_count++;
    return CALLBACK_CONTINUE;
}

static void print_json(const ScanOut *out, const YR_RULES *rules,
                       size_t rules_size, size_t target_size, int timeout,
                       bool include_offsets, int scan_result)
{
    size_t i, j;
    bool ok = scan_result == ERROR_SUCCESS;
    bool truncated = out->rules_truncated || out->matches_truncated;
    const char *stop = "completed";

    if (scan_result == ERROR_SCAN_TIMEOUT) stop = "timeout";
    else if (scan_result != ERROR_SUCCESS) stop = "scan_error";
    else if (out->rules_truncated) stop = "rule_limit";

    printf("{\"ok\":%s,\"engine\":\"yara\",\"targetSize\":%zu,\"rulesSize\":%zu"
           ",\"compiledRuleCount\":%u,\"matchedRuleCount\":%zu"
           ",\"timeoutSeconds\":%d,\"includeOffsets\":%s"
           ",\"ruleLimit\":%d,\"matchOffsetLimit\":%d"
           ",\"truncated\":%s,\"rulesTruncated\":%s,\"matchesTruncated\":%s"
           ",\"timedOut\":%s,\"stopReason\":",
           ok ? "true" : "false", target_size, rules_size,
           rules ? rules->num_rules : 0, out->rule_count, timeout,
           include_offsets ? "true" : "false", MAX_RULES, MAX_OFFSETS,
           truncated ? "true" : "false",
           out->rules_truncated ? "true" : "false",
           out->matches_truncated ? "true" : "false",
           scan_result == ERROR_SCAN_TIMEOUT ? "true" : "false");
    json_str(stdout, stop);
    fputs(",\"error\":", stdout);
    if (scan_result == ERROR_SUCCESS) {
        fputs("null", stdout);
    } else {
        printf("{\"code\":%d,\"name\":", scan_result);
        json_str(stdout, yara_error_name(scan_result));
        fputs(",\"message\":", stdout);
        json_str(stdout, yara_error_message(scan_result));
        fputc('}', stdout);
    }
    fputs(",\"rules\":[", stdout);
    for (i = 0; i < out->rule_count; i++) {
        const RuleOut *r = &out->rules[i];
        const YR_RULE *rule = r->rule;
        const char *tag;
        bool first = true;
        if (i) fputc(',', stdout);
        fputs("{\"rule\":", stdout);
        json_str(stdout, rule->identifier);
        fputs(",\"namespace\":", stdout);
        json_str(stdout, rule->ns ? rule->ns->name : "default");
        fputs(",\"tags\":[", stdout);
        yr_rule_tags_foreach(rule, tag) {
            if (!first) fputc(',', stdout);
            json_str(stdout, tag);
            first = false;
        }
        fputs("],\"matches\":[", stdout);
        for (j = 0; j < r->match_count; j++) {
            if (j) fputc(',', stdout);
            fputs("{\"string\":", stdout);
            json_str(stdout, r->matches[j].string);
            if (include_offsets)
                printf(",\"offset\":%lld", (long long)r->matches[j].offset);
            printf(",\"length\":%d}", (int)r->matches[j].length);
        }
        printf("],\"matchesTruncated\":%s}",
               r->matches_truncated ? "true" : "false");
    }
    fputs("]}\n", stdout);
    fflush(stdout);
}

static void usage(const char *argv0)
{
    fprintf(stderr, "usage: %s <rules_file> <target_file> <timeout_seconds 1..30> <include_offsets 0|1>\n",
            argv0);
}

int main(int argc, char **argv)
{
    unsigned char *rules_data = NULL, *target_data = NULL;
    size_t rules_size = 0, target_size = 0;
    char err[256];
    int timeout, include_arg, errors, result = 0, exit_code = 2;
    bool include_offsets, initialized = false, printed = false;
    YR_COMPILER *compiler = NULL;
    YR_RULES *rules = NULL;
    YR_SCANNER *scanner = NULL;
    ScanOut *out = NULL;
    CompileCtx compile_ctx;

    if (argc != 5) { usage(argv[0]); return 2; }
    if (!parse_int(argv[3], 1, 30, &timeout)) {
        fprintf(stderr, "yara_runner: timeout_seconds must be an integer in 1..30\n");
        return 2;
    }
    if (strcmp(argv[4], "0") == 0) include_offsets = false;
    else if (strcmp(argv[4], "1") == 0) include_offsets = true;
    else {
        fprintf(stderr, "yara_runner: include_offsets must be 0 or 1\n");
        return 2;
    }
    include_arg = include_offsets ? 1 : 0;

    if (!read_file(argv[1], RULES_LIMIT, &rules_data, &rules_size, err, sizeof(err))) {
        fprintf(stderr, "yara_runner: %s\n", err);
        goto done;
    }
    result = yr_initialize();
    if (result != ERROR_SUCCESS) {
        fprintf(stderr, "yara_runner: yr_initialize failed with code %d\n", result);
        goto done;
    }
    initialized = true;
    result = yr_compiler_create(&compiler);
    if (result != ERROR_SUCCESS) {
        fprintf(stderr, "yara_runner: yr_compiler_create failed with code %d\n", result);
        goto done;
    }
    compile_ctx.path = argv[1];
    yr_compiler_set_callback(compiler, compiler_cb, &compile_ctx);
    /* A NULL include callback disables include files entirely. */
    yr_compiler_set_include_callback(compiler, NULL, NULL, NULL);
    errors = yr_compiler_add_bytes(compiler, rules_data, rules_size, NULL);
    if (errors != 0) {
        fprintf(stderr, "yara_runner: rules compilation failed with %d error(s)\n", errors);
        exit_code = 3;
        goto done;
    }
    result = yr_compiler_get_rules(compiler, &rules);
    if (result != ERROR_SUCCESS) {
        fprintf(stderr, "yara_runner: yr_compiler_get_rules failed with code %d\n", result);
        exit_code = 3;
        goto done;
    }
    yr_compiler_destroy(compiler);
    compiler = NULL;

    if (!read_file(argv[2], TARGET_LIMIT, &target_data, &target_size, err, sizeof(err))) {
        fprintf(stderr, "yara_runner: %s\n", err);
        goto done;
    }
    out = (ScanOut *)calloc(1, sizeof(ScanOut));
    if (!out) {
        fprintf(stderr, "yara_runner: out of memory for scan results\n");
        goto done;
    }
    result = yr_scanner_create(rules, &scanner);
    if (result != ERROR_SUCCESS) {
        fprintf(stderr, "yara_runner: yr_scanner_create failed with code %d\n", result);
        goto done;
    }
    yr_scanner_set_callback(scanner, scan_cb, out);
    yr_scanner_set_timeout(scanner, timeout);
    yr_scanner_set_flags(scanner, SCAN_FLAGS_REPORT_RULES_MATCHING);
    result = yr_scanner_scan_mem(scanner, target_data, target_size);
    print_json(out, rules, rules_size, target_size, timeout, include_offsets, result);
    printed = true;
    exit_code = result == ERROR_SUCCESS ? 0 : 1;

done:
    if (!printed && result != ERROR_SUCCESS && initialized && compiler == NULL && rules)
        fprintf(stderr, "yara_runner: scan failed with code %d\n", result);
    if (scanner) yr_scanner_destroy(scanner);
    if (rules) yr_rules_destroy(rules);
    if (compiler) yr_compiler_destroy(compiler);
    free(rules_data);
    free(target_data);
    free(out);
    if (initialized) yr_finalize();
    (void)include_arg;
    return exit_code;
}
