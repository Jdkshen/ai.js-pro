package com.stardust.autojs.sjbc;

import java.util.ArrayList;
import java.util.List;

/**
 * SJBC 宿主函数表 —— **索引与 `pc-tools` 的 `HOST = ["print","len","push","slice0"]` 一一对应**：
 * 0=print（记录并原样返回）、1=len、2=push（**返回新数组**，纯函数语义）、3=slice0（前 n 个元素/字符）。
 * 少一个都会让编译产物把宿主调用当成未定义函数。
 */
public final class SjbcHostFunctions {

    public static final String[] NAMES = { "print", "len", "push", "slice0" };

    private SjbcHostFunctions() {
    }

    /** @param prints 收集 print 的值（与 oracle 的 host 数组语义一致） */
    public static SjbcVm.Host host(final List<Object> prints) {
        return new SjbcVm.Host() {
            @Override
            public Object call(int index, List<Object> args) {
                switch (index) {
                    case 0: {
                        Object value = args.isEmpty() ? SjbcVm.UNDEFINED : args.get(0);
                        prints.add(value);
                        return value;
                    }
                    case 1: return SjbcValues.number(SjbcValues.lengthOf(args.isEmpty() ? null : args.get(0)));
                    case 2: {
                        List<Object> copy = new ArrayList<>();
                        if (!args.isEmpty() && args.get(0) instanceof List) {
                            copy.addAll((List<?>) args.get(0));
                        }
                        copy.add(args.size() > 1 ? args.get(1) : SjbcVm.UNDEFINED);
                        return copy;
                    }
                    case 3: {
                        int n = args.size() > 1 ? (int) SjbcValues.toNumber(args.get(1)) : 0;
                        Object source = args.isEmpty() ? null : args.get(0);
                        if (source instanceof String) {
                            String text = (String) source;
                            return text.substring(0, Math.max(0, Math.min(n, text.length())));
                        }
                        List<Object> out = new ArrayList<>();
                        if (source instanceof List) {
                            List<?> list = (List<?>) source;
                            for (int i = 0; i < n && i < list.size(); i++) {
                                out.add(list.get(i));
                            }
                        }
                        return out;
                    }
                    default: throw new SjbcException("未知宿主函数索引 " + index + "（合法 0..3 = " + String.join(",", NAMES) + "）");
                }
            }
        };
    }
}