package com.stardust.autojs.runtime.api.shizuku;

import java.util.ArrayList;
import java.util.List;

/** Data stays arguments, including spaces, apostrophes and shell metacharacters. */
public final class ShizukuCommands {
    private ShizukuCommands() { }
    public static String quote(String text) {
        if (text == null || text.indexOf('\0') >= 0) throw new IllegalArgumentException("参数不能为null或包含NUL");
        return "'" + text.replace("'", "'\\''") + "'";
    }
    public static String argv(String[] args) {
        if (args == null || args.length == 0 || args.length > 256) throw new IllegalArgumentException("命令参数数量无效");
        StringBuilder result = new StringBuilder();
        for (String arg : args) { if (result.length() > 0) result.append(' '); result.append(quote(arg)); }
        if (result.length() > 131072) throw new IllegalArgumentException("命令过长");
        return result.toString();
    }
    public static void validateCommand(String command) {
        if (command == null || command.trim().isEmpty() || command.length() > 65536 || command.indexOf('\0') >= 0)
            throw new IllegalArgumentException("command必须为1..65536字符且不能包含NUL");
    }
    public static List<String> installOptions(String[] options, int defaultUser) {
        List<String> result = new ArrayList<>(); boolean hasUser = false;
        if (options != null) for (int i = 0; i < options.length; i++) {
            String value = options[i];
            if ("--user".equals(value)) {
                if (hasUser || ++i >= options.length || !options[i].matches("[0-9]+|current"))
                    throw new IllegalArgumentException("--user需要合法用户编号或current");
                hasUser = true; result.add(value); result.add(options[i]);
            } else if ("-r".equals(value) || "-d".equals(value) || "-t".equals(value) || "-g".equals(value)) result.add(value);
            else throw new IllegalArgumentException("不支持的安装选项：" + value);
        }
        if (!result.contains("-r")) result.add("-r");
        if (!hasUser) { result.add("--user"); result.add(String.valueOf(defaultUser)); }
        return result;
    }
}
