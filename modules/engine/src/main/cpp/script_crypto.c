/*
 * 脚本解密的原生实现（libaijscrypto.so）。
 *
 * 打包产物的脚本是「文件头 + AES/CBC/PKCS5 密文」，密钥由
 * SHA-256("aijspro-script-key|包名|随机盐|签名指纹") 派生（见 ScriptKeyDerivation）。
 * 以前这条链路全在 Java（javax.crypto），把派生逻辑和密钥都摊在 dex 里；
 * 现在下沉到这里：Java 只把「盐 + 指纹 + 密文」递进来，拿到明文就走，
 * 密钥不落到 Java 侧，反编译 dex 也看不到派生过程（属混淆加固，不是密码学上的提升）。
 *
 * 刻意做得自包含：不依赖 OpenSSL/QuickJS，也**不用 C++ 运行时**（纯 C 只调 libc），
 * 否则静态 libc++ 会让这个小库涨到几百 KB，每个 ABI 都要多背一份。
 * SHA-256 与 AES-256 都是现算实现（S 盒按 GF(2^8) 求逆 + 仿射变换生成，不写死 256 字节表），
 * 用 NIST 标准向量自检（nativeSelfTest），真机测试还会再和 Java 侧结果逐字节比对。
 */
#include <jni.h>

#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------ SHA-256 */

static const uint32_t kSha256K[64] = {
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u};

typedef struct {
    uint32_t state[8];
    uint64_t totalBits;
    uint8_t buffer[64];
    size_t buffered;
} Sha256;

static uint32_t sha256_rotr(uint32_t value, int bits) {
    return (value >> bits) | (value << (32 - bits));
}

static void sha256_compress(Sha256 *ctx, const uint8_t block[64]) {
    uint32_t w[64];
    int i;
    for (i = 0; i < 16; i++) {
        const size_t p = (size_t) i * 4;
        w[i] = ((uint32_t) block[p] << 24) | ((uint32_t) block[p + 1] << 16)
               | ((uint32_t) block[p + 2] << 8) | (uint32_t) block[p + 3];
    }
    for (i = 16; i < 64; i++) {
        const uint32_t s0 = sha256_rotr(w[i - 15], 7) ^ sha256_rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        const uint32_t s1 = sha256_rotr(w[i - 2], 17) ^ sha256_rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = ctx->state[0], b = ctx->state[1], c = ctx->state[2], d = ctx->state[3];
    uint32_t e = ctx->state[4], f = ctx->state[5], g = ctx->state[6], h = ctx->state[7];
    for (i = 0; i < 64; i++) {
        const uint32_t bigS1 = sha256_rotr(e, 6) ^ sha256_rotr(e, 11) ^ sha256_rotr(e, 25);
        const uint32_t ch = (e & f) ^ ((~e) & g);
        const uint32_t temp1 = h + bigS1 + ch + kSha256K[i] + w[i];
        const uint32_t bigS0 = sha256_rotr(a, 2) ^ sha256_rotr(a, 13) ^ sha256_rotr(a, 22);
        const uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        const uint32_t temp2 = bigS0 + maj;
        h = g; g = f; f = e; e = d + temp1; d = c; c = b; b = a; a = temp1 + temp2;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

static void sha256_init(Sha256 *ctx) {
    static const uint32_t kInit[8] = {0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
                                      0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u};
    memcpy(ctx->state, kInit, sizeof(kInit));
    ctx->totalBits = 0;
    ctx->buffered = 0;
}

static void sha256_update(Sha256 *ctx, const uint8_t *data, size_t length) {
    if (data == NULL || length == 0) {
        return;
    }
    ctx->totalBits += (uint64_t) length * 8;
    while (length > 0) {
        const size_t room = sizeof(ctx->buffer) - ctx->buffered;
        const size_t take = length < room ? length : room;
        memcpy(ctx->buffer + ctx->buffered, data, take);
        ctx->buffered += take;
        data += take;
        length -= take;
        if (ctx->buffered == sizeof(ctx->buffer)) {
            sha256_compress(ctx, ctx->buffer);
            ctx->buffered = 0;
        }
    }
}

static void sha256_final(Sha256 *ctx, uint8_t out[32]) {
    const uint64_t bitLength = ctx->totalBits;
    uint8_t pad = 0x80;
    int i;
    sha256_update(ctx, &pad, 1);
    pad = 0x00;
    while (ctx->buffered != 56) {
        sha256_update(ctx, &pad, 1);
    }
    /* 长度字段走 update 会重复计数，这里直接写进缓冲区再压缩。 */
    for (i = 0; i < 8; i++) {
        ctx->buffer[56 + i] = (uint8_t) ((bitLength >> (8 * (7 - i))) & 0xFF);
    }
    sha256_compress(ctx, ctx->buffer);
    ctx->buffered = 0;
    for (i = 0; i < 8; i++) {
        out[i * 4] = (uint8_t) (ctx->state[i] >> 24);
        out[i * 4 + 1] = (uint8_t) (ctx->state[i] >> 16);
        out[i * 4 + 2] = (uint8_t) (ctx->state[i] >> 8);
        out[i * 4 + 3] = (uint8_t) (ctx->state[i]);
    }
}

static void hex_lower(const uint8_t *data, size_t length, char *out) {
    static const char *kDigits = "0123456789abcdef";
    size_t i;
    for (i = 0; i < length; i++) {
        out[i * 2] = kDigits[data[i] >> 4];
        out[i * 2 + 1] = kDigits[data[i] & 0x0F];
    }
    out[length * 2] = '\0';
}

/* ------------------------------------------------------------------ AES-256 */

/** GF(2^8) 乘法（模 0x11B）。 */
static uint8_t gf_mul(uint8_t a, uint8_t b) {
    uint8_t product = 0;
    int i;
    for (i = 0; i < 8; i++) {
        if (b & 1) {
            product ^= a;
        }
        const uint8_t high = (uint8_t) (a & 0x80);
        a = (uint8_t) (a << 1);
        if (high) {
            a ^= 0x1B;
        }
        b = (uint8_t) (b >> 1);
    }
    return product;
}

static uint8_t gf_inverse(uint8_t value) {
    uint8_t result = 1;
    uint8_t base = value;
    int exponent = 254; /* a^254 == a^-1（a != 0） */
    if (value == 0) {
        return 0;
    }
    while (exponent > 0) {
        if (exponent & 1) {
            result = gf_mul(result, base);
        }
        base = gf_mul(base, base);
        exponent >>= 1;
    }
    return result;
}

static uint8_t rotl8(uint8_t value, int bits) {
    return (uint8_t) ((value << bits) | (value >> (8 - bits)));
}

/**
 * S 盒按定义现算：省掉 512 字节的写死表，也避免表被静态分析直接拿来复用。
 * 解密是低频操作（每个脚本一次），这点开销可以忽略。
 */
static void init_sboxes(uint8_t sbox[256], uint8_t inverseSbox[256]) {
    int i;
    for (i = 0; i < 256; i++) {
        const uint8_t b = gf_inverse((uint8_t) i);
        sbox[i] = (uint8_t) (b ^ rotl8(b, 1) ^ rotl8(b, 2) ^ rotl8(b, 3) ^ rotl8(b, 4) ^ 0x63);
    }
    for (i = 0; i < 256; i++) {
        inverseSbox[sbox[i]] = (uint8_t) i;
    }
}

/** AES-256 密钥扩展：Nk=8、Nr=14、共 60 个字。 */
static void aes256_expand_key(const uint8_t key[32], const uint8_t sbox[256], uint32_t w[60]) {
    int i;
    uint8_t rcon = 1;
    for (i = 0; i < 8; i++) {
        w[i] = ((uint32_t) key[i * 4] << 24) | ((uint32_t) key[i * 4 + 1] << 16)
               | ((uint32_t) key[i * 4 + 2] << 8) | (uint32_t) key[i * 4 + 3];
    }
    for (i = 8; i < 60; i++) {
        uint32_t temp = w[i - 1];
        if (i % 8 == 0) {
            temp = (temp << 8) | (temp >> 24); /* RotWord */
            temp = ((uint32_t) sbox[(temp >> 24) & 0xFF] << 24)
                   | ((uint32_t) sbox[(temp >> 16) & 0xFF] << 16)
                   | ((uint32_t) sbox[(temp >> 8) & 0xFF] << 8)
                   | (uint32_t) sbox[temp & 0xFF]; /* SubWord */
            temp ^= (uint32_t) rcon << 24;
            rcon = gf_mul(rcon, 2);
        } else if (i % 8 == 4) {
            temp = ((uint32_t) sbox[(temp >> 24) & 0xFF] << 24)
                   | ((uint32_t) sbox[(temp >> 16) & 0xFF] << 16)
                   | ((uint32_t) sbox[(temp >> 8) & 0xFF] << 8)
                   | (uint32_t) sbox[temp & 0xFF];
        }
        w[i] = w[i - 8] ^ temp;
    }
}

/** 状态按列存放：state[4 * column + row]。 */
static void add_round_key(uint8_t state[16], const uint32_t *w, int round) {
    int column;
    for (column = 0; column < 4; column++) {
        const uint32_t k = w[round * 4 + column];
        state[column * 4] ^= (uint8_t) ((k >> 24) & 0xFF);
        state[column * 4 + 1] ^= (uint8_t) ((k >> 16) & 0xFF);
        state[column * 4 + 2] ^= (uint8_t) ((k >> 8) & 0xFF);
        state[column * 4 + 3] ^= (uint8_t) (k & 0xFF);
    }
}

static void inv_shift_rows(uint8_t state[16]) {
    uint8_t shifted[16];
    int row, column;
    for (row = 0; row < 4; row++) {
        for (column = 0; column < 4; column++) {
            shifted[row + 4 * column] = state[row + 4 * ((column + 4 - row) % 4)];
        }
    }
    memcpy(state, shifted, 16);
}

static void inv_mix_columns(uint8_t state[16]) {
    int column;
    for (column = 0; column < 4; column++) {
        uint8_t *c = state + column * 4;
        const uint8_t a0 = c[0], a1 = c[1], a2 = c[2], a3 = c[3];
        c[0] = (uint8_t) (gf_mul(a0, 0x0E) ^ gf_mul(a1, 0x0B) ^ gf_mul(a2, 0x0D) ^ gf_mul(a3, 0x09));
        c[1] = (uint8_t) (gf_mul(a0, 0x09) ^ gf_mul(a1, 0x0E) ^ gf_mul(a2, 0x0B) ^ gf_mul(a3, 0x0D));
        c[2] = (uint8_t) (gf_mul(a0, 0x0D) ^ gf_mul(a1, 0x09) ^ gf_mul(a2, 0x0E) ^ gf_mul(a3, 0x0B));
        c[3] = (uint8_t) (gf_mul(a0, 0x0B) ^ gf_mul(a1, 0x0D) ^ gf_mul(a2, 0x09) ^ gf_mul(a3, 0x0E));
    }
}

static void aes256_decrypt_block(const uint8_t in[16], uint8_t out[16],
                                 const uint32_t w[60], const uint8_t inverseSbox[256]) {
    uint8_t state[16];
    int round, i;
    memcpy(state, in, 16);
    add_round_key(state, w, 14);
    for (round = 13; round >= 1; round--) {
        inv_shift_rows(state);
        for (i = 0; i < 16; i++) {
            state[i] = inverseSbox[state[i]];
        }
        add_round_key(state, w, round);
        inv_mix_columns(state);
    }
    inv_shift_rows(state);
    for (i = 0; i < 16; i++) {
        state[i] = inverseSbox[state[i]];
    }
    add_round_key(state, w, 0);
    memcpy(out, state, 16);
}

/** CBC 解密，就地覆写；长度必须是 16 的整数倍。 */
static void aes256_cbc_decrypt_inplace(uint8_t *buffer, size_t length,
                                       const uint8_t key[32], const uint8_t iv[16]) {
    uint8_t sbox[256];
    uint8_t inverseSbox[256];
    uint32_t roundKeys[60];
    uint8_t previous[16];
    size_t offset;
    init_sboxes(sbox, inverseSbox);
    aes256_expand_key(key, sbox, roundKeys);
    memcpy(previous, iv, 16);
    for (offset = 0; offset < length; offset += 16) {
        uint8_t cipherBlock[16];
        uint8_t plainBlock[16];
        int i;
        memcpy(cipherBlock, buffer + offset, 16);
        aes256_decrypt_block(cipherBlock, plainBlock, roundKeys, inverseSbox);
        for (i = 0; i < 16; i++) {
            buffer[offset + (size_t) i] = (uint8_t) (plainBlock[i] ^ previous[i]);
        }
        memcpy(previous, cipherBlock, 16);
    }
}

/* ------------------------------------------------------------------ 组合 */

#define CRYPTO_ERROR_MESSAGE_SIZE 128

static void copy_text(char *destination, size_t size, const char *text) {
    size_t i = 0;
    if (destination == NULL || size == 0) {
        return;
    }
    while (text[i] != '\0' && i + 1 < size) {
        destination[i] = text[i];
        i++;
    }
    destination[i] = '\0';
}

/**
 * 派生密钥并解密：key = SHA-256("aijspro-script-key|包名|盐|指纹") 的十六进制前 32 个字符，
 * IV = SHA-256("aijspro-script-vec|盐|指纹") 的十六进制前 16 个字符（与 Java 侧完全一致）。
 *
 * @return 明文长度；失败时返回 0 并写入 error
 */
static size_t decrypt_payload(uint8_t *buffer, size_t length,
                              const char *packageName, const char *salt, const char *fingerprint,
                              char *errorMessage, size_t errorSize) {
    static const char kKeyPrefix[] = "aijspro-script-key|";
    static const char kVectorPrefix[] = "aijspro-script-vec|";
    Sha256 ctx;
    uint8_t digest[32];
    char keyHex[65];
    char vectorHex[65];
    uint8_t key[32];
    uint8_t iv[16];
    size_t plainLength;
    uint8_t pad;

    if (length == 0 || length % 16 != 0) {
        copy_text(errorMessage, errorSize, "密文长度不是 16 字节的整数倍");
        return 0;
    }

    sha256_init(&ctx);
    sha256_update(&ctx, (const uint8_t *) kKeyPrefix, sizeof(kKeyPrefix) - 1);
    sha256_update(&ctx, (const uint8_t *) packageName, strlen(packageName));
    sha256_update(&ctx, (const uint8_t *) "|", 1);
    sha256_update(&ctx, (const uint8_t *) salt, strlen(salt));
    sha256_update(&ctx, (const uint8_t *) "|", 1);
    sha256_update(&ctx, (const uint8_t *) fingerprint, strlen(fingerprint));
    sha256_final(&ctx, digest);
    hex_lower(digest, sizeof(digest), keyHex);
    keyHex[32] = '\0';

    sha256_init(&ctx);
    sha256_update(&ctx, (const uint8_t *) kVectorPrefix, sizeof(kVectorPrefix) - 1);
    sha256_update(&ctx, (const uint8_t *) salt, strlen(salt));
    sha256_update(&ctx, (const uint8_t *) "|", 1);
    sha256_update(&ctx, (const uint8_t *) fingerprint, strlen(fingerprint));
    sha256_final(&ctx, digest);
    hex_lower(digest, sizeof(digest), vectorHex);
    vectorHex[16] = '\0';

    memcpy(key, keyHex, sizeof(key));
    memcpy(iv, vectorHex, sizeof(iv));

    aes256_cbc_decrypt_inplace(buffer, length, key, iv);

    pad = buffer[length - 1];
    if (pad == 0 || pad > 16 || (size_t) pad > length) {
        copy_text(errorMessage, errorSize, "PKCS5 去填充失败：填充值非法");
        return 0;
    }
    plainLength = length - (size_t) pad;
    while (plainLength < length) {
        if (buffer[plainLength] != pad) {
            copy_text(errorMessage, errorSize, "PKCS5 去填充失败：填充字节不一致");
            return 0;
        }
        plainLength++;
    }
    return length - (size_t) pad;
}

/** 用 NIST 标准向量自检；返回 0 表示通过，否则返回错误码并写入 message。 */
static int self_test(char *message, size_t messageSize) {
    static const char kSha256Abc[] = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    static const char kSha256Empty[] = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /* NIST SP 800-38A F.2.5 CBC-AES256：密钥 / IV / 密文 / 明文均取自标准示例。 */
    static const uint8_t kNistKey[32] = {
            0x60, 0x3d, 0xeb, 0x10, 0x15, 0xca, 0x71, 0xbe, 0x2b, 0x73, 0xae, 0xf0, 0x85, 0x7d, 0x77, 0x81,
            0x1f, 0x35, 0x2c, 0x07, 0x3b, 0x61, 0x08, 0xd7, 0x2d, 0x98, 0x10, 0xa3, 0x09, 0x14, 0xdf, 0xf4};
    static const uint8_t kNistIv[16] = {
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f};
    static const uint8_t kNistCipher[64] = {
            0xf5, 0x8c, 0x4c, 0x04, 0xd6, 0xe5, 0xf1, 0xba, 0x77, 0x9e, 0xab, 0xfb, 0x5f, 0x7b, 0xfb, 0xd6,
            0x9c, 0xfc, 0x4e, 0x96, 0x7e, 0xdb, 0x80, 0x8d, 0x67, 0x9f, 0x77, 0x7b, 0xc6, 0x70, 0x2c, 0x7d,
            0x39, 0xf2, 0x33, 0x69, 0xa9, 0xd9, 0xba, 0xcf, 0xa5, 0x30, 0xe2, 0x63, 0x04, 0x23, 0x14, 0x61,
            0xb2, 0xeb, 0x05, 0xe2, 0xc3, 0x9b, 0xe9, 0xfc, 0xda, 0x6c, 0x19, 0x07, 0x8c, 0x6a, 0x9d, 0x1b};
    static const char kNistPlainHex[] =
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"
            "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710";
    Sha256 ctx;
    uint8_t digest[32];
    char hex[129];
    uint8_t plain[64];

    sha256_init(&ctx);
    sha256_update(&ctx, (const uint8_t *) "abc", 3);
    sha256_final(&ctx, digest);
    hex_lower(digest, 32, hex);
    if (strcmp(hex, kSha256Abc) != 0) {
        copy_text(message, messageSize, hex);
        return 1;
    }
    sha256_init(&ctx);
    sha256_final(&ctx, digest);
    hex_lower(digest, 32, hex);
    if (strcmp(hex, kSha256Empty) != 0) {
        copy_text(message, messageSize, hex);
        return 2;
    }

    memcpy(plain, kNistCipher, sizeof(kNistCipher));
    aes256_cbc_decrypt_inplace(plain, sizeof(plain), kNistKey, kNistIv);
    hex_lower(plain, sizeof(plain), hex);
    if (strcmp(hex, kNistPlainHex) != 0) {
        copy_text(message, messageSize, hex);
        return 3;
    }
    return 0;
}

/* ------------------------------------------------------------------ 内嵌脚本载荷 */

/* 与 Java 侧 EmbeddedScriptFooter 完全一致的尾部格式：
 *   [库内容][脚本载荷(密文)][magic "AIJSPv1\0"][载荷长度小端 uint32][SHA-256(载荷)]
 * Java 侧负责写（打包时追加到工作区里的 .so），这里负责读（运行时从自己的 .so 尾部取回）。 */
static const uint8_t kFooterMagic[8] = {'A', 'I', 'J', 'S', 'P', 'v', '1', 0};
#define FOOTER_LENGTH_SIZE 4
#define FOOTER_DIGEST_SIZE 32
#define FOOTER_SIZE (sizeof(kFooterMagic) + FOOTER_LENGTH_SIZE + FOOTER_DIGEST_SIZE)
#define PAYLOAD_SIZE_LIMIT (512u * 1024u * 1024u)

/**
 * 从「库 + 载荷 + footer」字节里取出载荷。
 *
 * @return 载荷长度；格式不对/摘要不符时返回 0
 */
static size_t payload_from_library(const uint8_t *data, size_t size, uint8_t **payloadOut) {
    size_t footerStart;
    size_t length;
    size_t i;
    uint8_t digest[32];
    size_t payloadStart;

    *payloadOut = NULL;
    if (data == NULL || size < FOOTER_SIZE) {
        return 0;
    }
    footerStart = size - FOOTER_SIZE;
    if (memcmp(data + footerStart, kFooterMagic, sizeof(kFooterMagic)) != 0) {
        return 0;
    }
    length = (size_t) data[footerStart + sizeof(kFooterMagic)]
             | ((size_t) data[footerStart + sizeof(kFooterMagic) + 1] << 8)
             | ((size_t) data[footerStart + sizeof(kFooterMagic) + 2] << 16)
             | ((size_t) data[footerStart + sizeof(kFooterMagic) + 3] << 24);
    if (length == 0 || length > PAYLOAD_SIZE_LIMIT || length > footerStart) {
        return 0;
    }
    payloadStart = footerStart - length;
    /* 摘要校验：截断/改过的产物在这里就被挡掉，不会拿半截密文去解密。 */
    {
        Sha256 ctx;
        sha256_init(&ctx);
        sha256_update(&ctx, data + payloadStart, length);
        sha256_final(&ctx, digest);
    }
    for (i = 0; i < FOOTER_DIGEST_SIZE; i++) {
        if (digest[i] != data[footerStart + sizeof(kFooterMagic) + FOOTER_LENGTH_SIZE + i]) {
            return 0;
        }
    }
    {
        uint8_t *payload = (uint8_t *) malloc(length);
        if (payload == NULL) {
            return 0;
        }
        memcpy(payload, data + payloadStart, length);
        *payloadOut = payload;
    }
    return length;
}

/** 读整个文件（内容不大：这里只用于读自己的 .so，几十 MB 上限）。 */
static uint8_t *read_whole_file(const char *path, size_t *sizeOut) {
    FILE *fp = fopen(path, "rb");
    long fileSize;
    uint8_t *buffer;
    *sizeOut = 0;
    if (fp == NULL) {
        return NULL;
    }
    if (fseek(fp, 0, SEEK_END) != 0) {
        fclose(fp);
        return NULL;
    }
    fileSize = ftell(fp);
    if (fileSize <= 0) {
        fclose(fp);
        return NULL;
    }
    if (fseek(fp, 0, SEEK_SET) != 0) {
        fclose(fp);
        return NULL;
    }
    buffer = (uint8_t *) malloc((size_t) fileSize);
    if (buffer == NULL) {
        fclose(fp);
        return NULL;
    }
    if (fread(buffer, 1, (size_t) fileSize, fp) != (size_t) fileSize) {
        free(buffer);
        fclose(fp);
        return NULL;
    }
    fclose(fp);
    *sizeOut = (size_t) fileSize;
    return buffer;
}

/* 拿一个肯定存在的符号地址定位本库在磁盘上的路径：导出的 JNI 函数不会被优化掉。 */
extern jbyteArray JNICALL
Java_com_stardust_autojs_script_NativeScriptCrypto_nativeReadEmbeddedPayload(JNIEnv *, jclass);

/** 尾部格式自检（定义在后面，这里先声明）。 */
static int footer_self_test(char *message, size_t messageSize);

/* ------------------------------------------------------------------ JNI */

static void throw_illegal_argument(JNIEnv *env, const char *message) {
    jclass exceptionClass = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    (*env)->ThrowNew(env, exceptionClass, message);
}

static char *to_utf8(JNIEnv *env, jstring value, const char *fallback) {
    const char *chars;
    char *copy;
    size_t length;
    if (value == NULL) {
        return strdup(fallback);
    }
    chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (chars == NULL) {
        return NULL;
    }
    length = strlen(chars);
    copy = (char *) malloc(length + 1);
    if (copy != NULL) {
        memcpy(copy, chars, length + 1);
    }
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return copy;
}

JNIEXPORT jbyteArray JNICALL
Java_com_stardust_autojs_script_NativeScriptCrypto_nativeDecrypt(
        JNIEnv *env, jclass clazz, jbyteArray data, jint offset, jint length,
        jstring packageName, jstring salt, jstring fingerprint) {
    jbyte *raw;
    jsize total;
    uint8_t *buffer;
    size_t plainLength;
    char errorMessage[CRYPTO_ERROR_MESSAGE_SIZE];
    char *packageNameUtf8;
    char *saltUtf8;
    char *fingerprintUtf8;
    jbyteArray result;

    (void) clazz;
    if (data == NULL || offset < 0 || length < 0) {
        throw_illegal_argument(env, "解密参数非法");
        return NULL;
    }
    total = (*env)->GetArrayLength(env, data);
    if (offset > total || length > total - offset) {
        throw_illegal_argument(env, "解密范围越界");
        return NULL;
    }
    errorMessage[0] = '\0';
    buffer = (uint8_t *) malloc((size_t) length);
    if (buffer == NULL) {
        throw_illegal_argument(env, "解密缓冲区申请失败");
        return NULL;
    }
    raw = (*env)->GetByteArrayElements(env, data, NULL);
    if (raw == NULL) {
        free(buffer);
        throw_illegal_argument(env, "读取密文失败");
        return NULL;
    }
    memcpy(buffer, raw + offset, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, data, raw, JNI_ABORT);

    packageNameUtf8 = to_utf8(env, packageName, "");
    saltUtf8 = to_utf8(env, salt, "");
    fingerprintUtf8 = to_utf8(env, fingerprint, "");
    if (packageNameUtf8 == NULL || saltUtf8 == NULL || fingerprintUtf8 == NULL) {
        free(buffer);
        free(packageNameUtf8);
        free(saltUtf8);
        free(fingerprintUtf8);
        throw_illegal_argument(env, "读取派生材料失败");
        return NULL;
    }

    plainLength = decrypt_payload(buffer, (size_t) length,
                                  packageNameUtf8, saltUtf8, fingerprintUtf8,
                                  errorMessage, sizeof(errorMessage));
    free(packageNameUtf8);
    free(saltUtf8);
    free(fingerprintUtf8);
    if (plainLength == 0) {
        free(buffer);
        throw_illegal_argument(env, errorMessage[0] == '\0' ? "解密失败" : errorMessage);
        return NULL;
    }

    result = (*env)->NewByteArray(env, (jsize) plainLength);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) plainLength, (const jbyte *) buffer);
    }
    free(buffer);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_stardust_autojs_script_NativeScriptCrypto_nativeSelfTest(JNIEnv *env, jclass clazz) {
    char message[CRYPTO_ERROR_MESSAGE_SIZE];
    (void) clazz;
    if (self_test(message, sizeof(message)) != 0) {
        return (*env)->NewStringUTF(env, message);
    }
    if (footer_self_test(message, sizeof(message)) != 0) {
        return (*env)->NewStringUTF(env, message);
    }
    return (*env)->NewStringUTF(env, "");
}

/** 自检尾部格式：自己造一份「库+载荷+footer」，再读回来比对。 */
static int footer_self_test(char *message, size_t messageSize) {
    static const uint8_t kFakeLibrary[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    static const uint8_t kFakePayload[5] = {9, 8, 7, 6, 5};
    uint8_t buffer[sizeof(kFakeLibrary) + sizeof(kFakePayload) + FOOTER_SIZE];
    Sha256 ctx;
    uint8_t digest[32];
    uint8_t *payload = NULL;
    size_t payloadSize;
    size_t i;
    size_t footerStart = sizeof(kFakeLibrary) + sizeof(kFakePayload);

    memcpy(buffer, kFakeLibrary, sizeof(kFakeLibrary));
    memcpy(buffer + sizeof(kFakeLibrary), kFakePayload, sizeof(kFakePayload));
    memcpy(buffer + footerStart, kFooterMagic, sizeof(kFooterMagic));
    for (i = 0; i < 4; i++) {
        buffer[footerStart + sizeof(kFooterMagic) + i] =
                (uint8_t) ((sizeof(kFakePayload) >> (8 * i)) & 0xFF);
    }
    sha256_init(&ctx);
    sha256_update(&ctx, kFakePayload, sizeof(kFakePayload));
    sha256_final(&ctx, digest);
    memcpy(buffer + footerStart + sizeof(kFooterMagic) + 4, digest, sizeof(digest));

    payloadSize = payload_from_library(buffer, sizeof(buffer), &payload);
    if (payloadSize != sizeof(kFakePayload) || payload == NULL) {
        copy_text(message, messageSize, "尾部格式自检失败：载荷长度不符");
        free(payload);
        return 4;
    }
    for (i = 0; i < sizeof(kFakePayload); i++) {
        if (payload[i] != kFakePayload[i]) {
            copy_text(message, messageSize, "尾部格式自检失败：载荷内容不符");
            free(payload);
            return 5;
        }
    }
    free(payload);

    /* 载荷被改动时必须读不出来（摘要只覆盖载荷，所以改载荷里的字节）。 */
    buffer[sizeof(kFakeLibrary)] ^= 0xFF;
    payloadSize = payload_from_library(buffer, sizeof(buffer), &payload);
    free(payload);
    if (payloadSize != 0) {
        copy_text(message, messageSize, "尾部格式自检失败：篡改未被发现");
        return 6;
    }
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_stardust_autojs_script_NativeScriptCrypto_nativeReadEmbeddedPayload(JNIEnv *env, jclass clazz) {
    Dl_info info;
    uint8_t *fileData;
    size_t fileSize;
    uint8_t *payload = NULL;
    size_t payloadSize;
    jbyteArray result;

    (void) clazz;
    if (dladdr((void *) (uintptr_t) &Java_com_stardust_autojs_script_NativeScriptCrypto_nativeReadEmbeddedPayload,
               &info) == 0 || info.dli_fname == NULL) {
        return NULL;
    }
    /* 库直接从 APK 里映射时路径形如 xxx.apk!/lib/...（没落地成文件），这里读不到就给上层报错。 */
    if (strstr(info.dli_fname, "!/") != NULL) {
        return NULL;
    }
    fileData = read_whole_file(info.dli_fname, &fileSize);
    if (fileData == NULL) {
        return NULL;
    }
    payloadSize = payload_from_library(fileData, fileSize, &payload);
    free(fileData);
    if (payloadSize == 0 || payload == NULL) {
        free(payload);
        return NULL;
    }
    result = (*env)->NewByteArray(env, (jsize) payloadSize);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) payloadSize, (const jbyte *) payload);
    }
    free(payload);
    return result;
}
