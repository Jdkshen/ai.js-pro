package com.stardust.autojs.sjbc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SJBC 字节码 VM —— **逐行移植用户参考实现 `pc-tools/vm.js`**（M4 指令集，m18 后的最终语义）。
 *
 * <p><b>步数口径（与 oracle 一致，写死在这里）</b>：oracle 主循环是
 * {@code while (pc < code.length) { if (++steps > 5e7) throw } } ⇒ steps = 指令**派发次数**}：
 * 每条指令（含 {@code NOP/TRY/ENDTRY/PRINT/JMP/CALL*}）都只 +1；<b>{@code LOADF} 虽然长 6 字节也算 1 步</b>
 * （长度只用于推进 pc，见 {@link #LEN}）；{@code RET}/{@code RETV}(栈空)/未捕获 {@code THROW} 返回时不再加，
 * {@code THROW} 弹 handler 的循环不计步。
 *
 * <p><b>entries 语义</b>：{@code entries[fnId] = 该函数入口 PC}（不是池下标）⇒ 调用时 {@code pc = entries[fnId]}；
 * 因此 {@code entries.length == 函数数}，入口 PC 必须落在码内。
 *
 * <p>忠实保留 oracle 的几处「怪但必须一样」的语义：{@code RET} 直接结束整个调用（不管栈深）；
 * 除零/模零回 0；{@code ADD} 任一侧是字符串就拼接；比较回 1/0；{@code EQ/NE} 严格相等；
 * {@code ASET/OSET} 用 {@code R[rd]} 当容器、{@code R[ra]} 当下标、{@code R[rd+1]} 当值。
 * JS 的 {@code undefined} 用 {@link #UNDEFINED} 表示（与 {@code null} 严格不等，保持 oracle 语义）。
 */
public final class SjbcVm {

    public static final int REGN = 16;
    public static final long MAX_STEPS = 50_000_000L;
    /** 指令长度表：只有 LOADF 是 6 字节，其余 4（跳转目标按**真实长度**，不是固定 4）。 */
    public static final int[] LEN = new int[256];
    static {
        java.util.Arrays.fill(LEN, 4);
        LEN[0x02] = 6; // LOADF
    }
    /** JS undefined。 */
    public static final Object UNDEFINED = new Object() {
        @Override
        public String toString() {
            return "undefined";
        }
    };

    private SjbcVm() {
    }

    /** 闭包：函数入口 PC + 捕获的单元（upvals）。 */
    public static final class Closure {
        public final int entry;
        public final List<Object> upvals;

        Closure(int entry, List<Object> upvals) {
            this.entry = entry;
            this.upvals = upvals;
        }
    }

    /** 一次运行的观测结果（与 oracle 的 {log, steps, uncaught} 对齐）。 */
    public static final class Result {
        public final long steps;
        /** print / 宿主调用按发生顺序的记录；{@code type=print|host}。 */
        public final List<Event> log;
        /** 未捕获异常值；null 表示正常结束。 */
        public final Object uncaught;

        Result(long steps, List<Event> log, Object uncaught) {
            this.steps = steps;
            this.log = log;
            this.uncaught = uncaught;
        }

        /** 只取 print 的值（对拍用）。 */
        public List<Object> prints() {
            List<Object> out = new ArrayList<>();
            for (Event e : log) {
                if ("print".equals(e.type)) {
                    out.add(e.value);
                }
            }
            return out;
        }
    }

    /** 一条记录：host 调用带 {@code fidx}/{@code args}，print 带 {@code value}。 */
    public static final class Event {
        public final String type;
        public final int fidx;
        public final Object value;
        public final List<Object> args;

        Event(String type, int fidx, Object value, List<Object> args) {
            this.type = type;
            this.fidx = fidx;
            this.value = value;
            this.args = args;
        }

        @Override
        public String toString() {
            return "print".equals(type) ? "print(" + SjbcValues.toDisplayString(value) + ")"
                    : "host[" + fidx + "](" + args + ")";
        }
    }

    private static final class Frame {
        final Object[] registers = new Object[REGN];
        final List<Object> upvals;
        int retPc = -1;
        int retReg;
        Object pendingExc;
        int pendingExcSet; // 0=无, 1=有（区分「异常值为 null/undefined」）

        Frame(List<Object> upvals) {
            this.upvals = upvals;
            java.util.Arrays.fill(registers, SjbcValues.number(0));
        }
    }

    private static final class Handler {
        final int depth;
        final int target;

        Handler(int depth, int target) {
            this.depth = depth;
            this.target = target;
        }
    }

    /** 宿主函数表（索引 0..3 与 pc-tools 的 HOST=[print,len,push,slice0] 一一对应）。 */
    public interface Host {
        Object call(int index, List<Object> args);
    }

    /** 默认宿主：与用户 README 的语义一致（见 {@link SjbcHostFunctions}）。 */
    public static Host defaultHost(List<Object> prints) {
        return SjbcHostFunctions.host(prints);
    }

    public static Result run(byte[] code, List<Object> pool, Host host, int[] entries) {
        return run(code, pool, host, entries, 0);
    }

    /** 执行入口：{@code entries[entryIndex]} 为起始 PC。 */
    public static Result run(byte[] code, List<Object> pool, Host host, int[] entries, int entryIndex) {
        if (code == null || entries == null || entries.length == 0 || entryIndex < 0 || entryIndex >= entries.length) {
            throw new SjbcException("SJBC 执行参数不合法（code/entries/entryIndex）");
        }
        List<Frame> stack = new ArrayList<>();
        stack.add(new Frame(new ArrayList<>()));
        List<Handler> handlers = new ArrayList<>();
        List<Object> captureStack = new ArrayList<>();
        List<Event> log = new ArrayList<>();
        long steps = 0;
        int pc = entries[entryIndex];
        while (pc < code.length) {
            if (++steps > MAX_STEPS) {
                throw new SjbcException("SJBC 步数超过上限 " + MAX_STEPS);
            }
            Frame frame = stack.get(stack.size() - 1);
            Object[] r = frame.registers;
            int op = code[pc] & 0xFF;
            int u1 = code[pc + 1] & 0xFF;
            int u2 = code[pc + 2] & 0xFF;
            int u3 = code[pc + 3] & 0xFF;
            int rd = (u1 >> 4) & 0xF;
            int ra = u1 & 0xF;
            int rb = (u2 >> 4) & 0xF;
            int imm = (u2 << 8) | u3;
            int ln = LEN[op];
            switch (op) {
                case 0x00: break;                                   // NOP
                case 0x01: r[rd] = poolValue(pool, imm); break;      // LOADK
                case 0x04: r[rd] = poolValue(pool, imm); break;      // STR
                case 0x03: r[rd] = r[ra]; break;                     // MOV
                case 0x02: {                                        // LOADF（6 字节，内嵌 f32）
                    int bits = ((u2 << 24) | (u3 << 16) | ((code[pc + 4] & 0xFF) << 8) | (code[pc + 5] & 0xFF));
                    r[rd] = SjbcValues.number(Float.intBitsToFloat(bits));
                    break;
                }
                case 0x05: {                                        // CLOSURE
                    int fidx = u2;
                    int ncap = u3;
                    List<Object> caps = new ArrayList<>();
                    for (int i = 0; i < ncap; i++) {
                        caps.add(0, captureStack.remove(captureStack.size() - 1));
                    }
                    r[rd] = new Closure(fidx, caps);
                    break;
                }
                case 0x06: captureStack.add(r[ra]); break;           // CAPTURE
                case 0x07: {                                        // NEWCELL
                    List<Object> cell = new ArrayList<>(1);
                    cell.add(r[ra]);
                    r[rd] = cell;
                    break;
                }
                case 0x08: r[rd] = cellGet(r[ra]); break;            // GETCELL
                case 0x09: cellSet(r[rd], r[ra]); break;             // SETCELL
                case 0x0a: r[rd] = upval(frame, imm); break;         // GETUP
                case 0x0b: setUpval(frame, imm, r[ra]); break;       // SETUP
                case 0x10: r[rd] = SjbcValues.add(r[ra], r[rb]); break;
                case 0x11: r[rd] = SjbcValues.number(SjbcValues.toNumber(r[ra]) - SjbcValues.toNumber(r[rb])); break;
                case 0x12: r[rd] = SjbcValues.number(SjbcValues.toNumber(r[ra]) * SjbcValues.toNumber(r[rb])); break;
                case 0x13: {                                        // DIV：除零回 0（oracle 语义）
                    double b = SjbcValues.toNumber(r[rb]);
                    r[rd] = SjbcValues.number(b == 0 ? 0 : SjbcValues.toNumber(r[ra]) / b);
                    break;
                }
                case 0x14: {                                        // MOD：模零回 0
                    double b = SjbcValues.toNumber(r[rb]);
                    r[rd] = SjbcValues.number(b == 0 ? 0 : SjbcValues.toNumber(r[ra]) % b);
                    break;
                }
                case 0x15: r[rd] = SjbcValues.number(-SjbcValues.toNumber(r[ra])); break;
                case 0x20: r[rd] = SjbcValues.number(SjbcValues.strictEquals(r[ra], r[rb]) ? 1 : 0); break;
                case 0x21: r[rd] = SjbcValues.number(SjbcValues.strictEquals(r[ra], r[rb]) ? 0 : 1); break;
                case 0x22: r[rd] = SjbcValues.number(SjbcValues.compare(r[ra], r[rb]) < 0 ? 1 : 0); break;
                case 0x23: r[rd] = SjbcValues.number(SjbcValues.compare(r[ra], r[rb]) <= 0 ? 1 : 0); break;
                case 0x24: r[rd] = SjbcValues.number(SjbcValues.compare(r[ra], r[rb]) > 0 ? 1 : 0); break;
                case 0x25: r[rd] = SjbcValues.number(SjbcValues.compare(r[ra], r[rb]) >= 0 ? 1 : 0); break;
                case 0x30: pc = imm; continue;                      // JMP
                case 0x31: if (!SjbcValues.isNumberZero(r[ra])) { pc = imm; continue; } break;
                case 0x32: if (SjbcValues.isNumberZero(r[ra])) { pc = imm; continue; } break;
                case 0x40: {                                        // NEWARR
                    List<Object> arr = new ArrayList<>(Math.max(0, imm));
                    for (int i = 0; i < imm; i++) {
                        arr.add(SjbcValues.number(0));
                    }
                    r[rd] = arr;
                    break;
                }
                case 0x41: r[rd] = SjbcValues.number(SjbcValues.lengthOf(r[ra])); break;
                case 0x42: r[rd] = SjbcValues.indexGet(r[ra], r[rb]); break;
                case 0x43: SjbcValues.indexSet(r[rd], r[ra], r[rd + 1]); break;
                case 0x48: r[rd] = new LinkedHashMap<String, Object>(); break;
                case 0x49: r[rd] = SjbcValues.keyGet(r[ra], r[rb]); break;
                case 0x4a: SjbcValues.keySet(r[rd], r[ra], r[rd + 1]); break;
                case 0x50: {                                        // CALL：宿主函数（fidx = 宿主索引）
                    int fidx = u2;
                    int argc = u3;
                    int base = rd;
                    List<Object> args = new ArrayList<>(argc);
                    for (int i = 0; i < argc; i++) {
                        args.add(r[base + i]);
                    }
                    Object value = host == null ? UNDEFINED : host.call(fidx, args);
                    r[base] = value;
                    log.add(new Event("host", fidx, value, args));
                    break;
                }
                case 0x52: {                                        // CALLF：函数 id → entries[fidx]
                    int fidx = u2;
                    int argc = u3;
                    int base = rd;
                    Frame callee = new Frame(new ArrayList<>());
                    for (int i = 0; i < argc; i++) {
                        callee.registers[i] = r[base + i];
                    }
                    callee.retPc = pc + ln;
                    callee.retReg = base;
                    stack.add(callee);
                    pc = entryOf(entries, fidx);
                    continue;
                }
                case 0x54: {                                        // CALLV：闭包
                    int argc = u3;
                    int base = rd;
                    Object fn = r[base];
                    if (!(fn instanceof Closure)) {
                        throw new SjbcException("CALLV 的目标不是闭包：" + fn);
                    }
                    Closure closure = (Closure) fn;
                    Frame callee = new Frame(new ArrayList<>(closure.upvals));
                    for (int i = 0; i < argc; i++) {
                        callee.registers[i] = r[base + 1 + i];
                    }
                    callee.retPc = pc + ln;
                    callee.retReg = base;
                    stack.add(callee);
                    pc = entryOf(entries, closure.entry);
                    continue;
                }
                case 0x53: {                                        // RETV
                    Object value = r[ra];
                    Frame finished = stack.remove(stack.size() - 1);
                    if (stack.isEmpty()) {
                        return new Result(steps, log, null);
                    }
                    Frame caller = stack.get(stack.size() - 1);
                    caller.registers[finished.retReg] = value;
                    pc = finished.retPc;
                    continue;
                }
                case 0x70: handlers.add(new Handler(stack.size() - 1, imm)); break; // TRY
                case 0x71: {                                        // ENDTRY：退掉同深度的最近 handler
                    for (int i = handlers.size() - 1; i >= 0; i--) {
                        if (handlers.get(i).depth == stack.size() - 1) {
                            handlers.remove(i);
                            break;
                        }
                    }
                    break;
                }
                case 0x72: {                                        // THROW
                    Object value = r[ra];
                    boolean caught = false;
                    while (!handlers.isEmpty()) {
                        Handler handler = handlers.remove(handlers.size() - 1);
                        if (handler.depth <= stack.size() - 1) {
                            while (stack.size() - 1 > handler.depth) {
                                stack.remove(stack.size() - 1);
                            }
                            Frame target = stack.get(stack.size() - 1);
                            target.pendingExc = value;
                            target.pendingExcSet = 1;
                            pc = handler.target;
                            caught = true;
                            break;
                        }
                    }
                    if (!caught) {
                        return new Result(steps, log, value);
                    }
                    continue;
                }
                case 0x73: {                                        // GETEXC
                    Frame top = stack.get(stack.size() - 1);
                    r[rd] = top.pendingExcSet == 1 ? top.pendingExc : UNDEFINED;
                    top.pendingExc = null;
                    top.pendingExcSet = 0;
                    break;
                }
                case 0x60: {                                        // PRINT
                    Object value = r[ra];
                    log.add(new Event("print", -1, value, null));
                    break;
                }
                case 0x51: return new Result(steps, log, null);     // RET：oracle 里直接结束整个调用
                default: throw new SjbcException(String.format("未知 SJBC 指令 0x%02x @pc=%d", op, pc));
            }
            pc += ln;
        }
        return new Result(steps, log, null);
    }

    private static int entryOf(int[] entries, int fnId) {
        if (fnId < 0 || fnId >= entries.length) {
            throw new SjbcException("函数 id 越界：" + fnId + "（entries 长度 " + entries.length + "）");
        }
        return entries[fnId];
    }

    private static Object poolValue(List<Object> pool, int index) {
        if (pool == null || index < 0 || index >= pool.size()) {
            return UNDEFINED;
        }
        Object value = pool.get(index);
        return value == null ? UNDEFINED : value;
    }

    private static Object upval(Frame frame, int index) {
        return index >= 0 && index < frame.upvals.size() ? frame.upvals.get(index) : UNDEFINED;
    }

    private static void setUpval(Frame frame, int index, Object value) {
        while (frame.upvals.size() <= index) {
            frame.upvals.add(UNDEFINED);
        }
        frame.upvals.set(index, value);
    }

    private static Object cellGet(Object cell) {
        return cell instanceof List && !((List<?>) cell).isEmpty() ? ((List<?>) cell).get(0) : UNDEFINED;
    }

    @SuppressWarnings("unchecked")
    private static void cellSet(Object cell, Object value) {
        if (cell instanceof List) {
            List<Object> list = (List<Object>) cell;
            if (list.isEmpty()) {
                list.add(value);
            } else {
                list.set(0, value);
            }
        }
    }

    /** 便于测试/对拍：把 entries/pool 从容器结果里取出来。 */
    public static Map<String, Object> describe(SjbcV5Container.Opened opened) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", opened.code);
        map.put("pool", opened.pool);
        map.put("entries", opened.entries);
        return map;
    }
}