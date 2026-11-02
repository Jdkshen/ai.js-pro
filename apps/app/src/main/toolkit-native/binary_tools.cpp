#include <jni.h>
#include <capstone/capstone.h>
#include <string>
#include <sstream>
#include <iomanip>
#include <vector>

static std::string quote(const char* value) {
    std::string result = "\"";
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(value); *p; ++p) {
        if (*p == '\\' || *p == '"') { result += '\\'; result += static_cast<char>(*p); }
        else if (*p < 32) { char temp[7]; snprintf(temp, sizeof(temp), "\\u%04x", *p); result += temp; }
        else result += static_cast<char>(*p);
    }
    return result + '"';
}
static std::string hex(uint64_t value) { std::ostringstream s; s << "0x" << std::hex << value; return s.str(); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_jdkshen_aijspro_mcp_ToolkitNative_decodeWindow(JNIEnv* env, jobject, jbyteArray input,
        jint machine, jboolean little, jlong address, jint mode, jint maxInstructions) {
    if (!input || env->GetArrayLength(input) > 65536 || maxInstructions < 1 || maxInstructions > 16384) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Native instruction window exceeds bounds"); return nullptr;
    }
    cs_arch arch; cs_mode bits;
    if (machine == 183) { arch = CS_ARCH_ARM64; bits = CS_MODE_ARM; }
    else if (machine == 40) { arch = CS_ARCH_ARM; bits = mode == 1 ? CS_MODE_THUMB : CS_MODE_ARM; }
    else if (machine == 62 || machine == 3) { arch = CS_ARCH_X86; bits = machine == 62 ? CS_MODE_64 : CS_MODE_32; }
    else { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Unsupported instruction architecture"); return nullptr; }
    if (!little) bits = static_cast<cs_mode>(bits | CS_MODE_BIG_ENDIAN);
    csh handle;
    if (cs_open(arch, bits, &handle) != CS_ERR_OK) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Cannot initialize instruction decoder"); return nullptr;
    }
    cs_option(handle, CS_OPT_DETAIL, CS_OPT_ON);
    cs_option(handle, CS_OPT_SKIPDATA, CS_OPT_ON);
    std::vector<uint8_t> bytes(env->GetArrayLength(input));
    env->GetByteArrayRegion(input, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) { cs_close(&handle); return nullptr; }
    cs_insn* instructions = nullptr;
    size_t count = cs_disasm(handle, bytes.data(), bytes.size(), static_cast<uint64_t>(address), maxInstructions, &instructions);
    std::string result = "[";
    for (size_t i = 0; i < count; ++i) {
        const cs_insn& ins = instructions[i]; const bool decoded = ins.detail != nullptr;
        const bool call = decoded && cs_insn_group(handle, &ins, CS_GRP_CALL);
        const bool jump = decoded && cs_insn_group(handle, &ins, CS_GRP_JUMP);
        const bool ret = decoded && cs_insn_group(handle, &ins, CS_GRP_RET);
        bool conditional = false; bool hasTarget = false; uint64_t target = 0;
        if (decoded && arch == CS_ARCH_ARM64) {
            const cs_arm64& detail = ins.detail->arm64;
            conditional = jump && (detail.cc != ARM64_CC_INVALID && detail.cc != ARM64_CC_AL);
            conditional = conditional || ins.id == ARM64_INS_CBZ || ins.id == ARM64_INS_CBNZ || ins.id == ARM64_INS_TBZ || ins.id == ARM64_INS_TBNZ;
            const bool data = ins.id == ARM64_INS_ADR || ins.id == ARM64_INS_ADRP || ins.id == ARM64_INS_LDR || ins.id == ARM64_INS_LDRSW || ins.id == ARM64_INS_PRFM;
            for (int n = 0; n < detail.op_count; ++n) if (detail.operands[n].type == ARM64_OP_IMM && (call || jump || data)) {
                target = static_cast<uint64_t>(detail.operands[n].imm); hasTarget = true;
            }
        } else if (decoded && arch == CS_ARCH_ARM) {
            const cs_arm& detail = ins.detail->arm;
            conditional = jump && detail.cc != ARM_CC_INVALID && detail.cc != ARM_CC_AL;
            conditional = conditional || ins.id == ARM_INS_CBZ || ins.id == ARM_INS_CBNZ;
            for (int n = 0; n < detail.op_count; ++n) {
                const cs_arm_op& operand = detail.operands[n];
                if (operand.type == ARM_OP_IMM && (jump || call)) { target = static_cast<uint64_t>(operand.imm); hasTarget = true; }
                if (operand.type == ARM_OP_MEM && operand.mem.base == ARM_REG_PC) {
                    target = ((ins.address + (mode == 1 ? 4 : 8)) & ~uint64_t(3)) + operand.mem.disp; hasTarget = true;
                }
            }
        } else if (decoded && arch == CS_ARCH_X86) {
            const cs_x86& detail = ins.detail->x86;
            conditional = jump && ins.id != X86_INS_JMP && ins.id != X86_INS_LJMP;
            for (int n = 0; n < detail.op_count; ++n) {
                const cs_x86_op& operand = detail.operands[n];
                if (operand.type == X86_OP_IMM && (jump || call)) { target = static_cast<uint64_t>(operand.imm); hasTarget = true; }
                if (operand.type == X86_OP_MEM && operand.mem.base == X86_REG_RIP) { target = ins.address + ins.size + operand.mem.disp; hasTarget = true; }
            }
        }
        std::string bytesHex; const char* digits = "0123456789ABCDEF";
        for (int n = 0; n < ins.size; ++n) { bytesHex += digits[ins.bytes[n] >> 4]; bytesHex += digits[ins.bytes[n] & 15]; }
        const std::string kind = call ? "call" : jump ? "jump" : hasTarget ? "data" : "unknown";
        if (i) result += ',';
        result += "{\"address\":" + quote(hex(ins.address).c_str()) + ",\"offset\":" + std::to_string(ins.address - static_cast<uint64_t>(address)) +
            ",\"size\":" + std::to_string(ins.size) + ",\"hex\":" + quote(bytesHex.c_str()) + ",\"mnemonic\":" + quote(ins.mnemonic) +
            ",\"operands\":" + quote(ins.op_str) + ",\"target\":" + (hasTarget ? quote(hex(target).c_str()) : "null") +
            ",\"kind\":" + quote(kind.c_str()) + ",\"conditional\":" + (conditional ? "true" : "false") +
            ",\"terminal\":" + ((ret || (jump && !conditional)) ? "true" : "false") + ",\"decoded\":" + (decoded ? "true" : "false") + "}";
    }
    result += ']'; cs_free(instructions, count); cs_close(&handle);
    return env->NewStringUTF(result.c_str());
}
