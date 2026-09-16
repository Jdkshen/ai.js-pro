// @engine rhino
// UI 里显示控制台：把日志嵌进页面 —— <console>（跟随本脚本）与 <globalconsole>（全局日志）
//
// 说明：Auto.js **Pro** 的原版示例还用了 $ui / appbar / toolbar / viewpager / bottomnaviagtion / fab
// 等 Pro 专属组件，本引擎没有实现（见 docs/architecture/pro-api-compatibility.csv 的 2.36 一节），
// 所以这里只演示「控制台组件」本身的最小可用写法；去掉的那些不影响这两块控制台。
"ui";

ui.layout(
    <vertical>
        <button id="log" text="打印 4 条不同等级的日志" w="*" h="auto" margin="8 8 8 0"/>
        <button id="clear" text="清空" w="*" h="auto" margin="8"/>
        <console id="c" w="*" h="500"/>
        <globalconsole id="gc" w="*" h="*"/>
    </vertical>
);

// ① <console> 要自己绑：把页面里这块控制台接到「本脚本的日志」上。
//    ⚠️ 必须传 runtime.console（Java 的控制台对象）；脚本里那个 console 是包装过的 JS 对象，传进去不生效。
ui.c.setConsole(runtime.console);

// ② 常用设置。等级字母：V 明细 / D 调试 / I 信息 / W 警告 / E 错误 / A 断言
ui.c.setInputEnabled(false);   // 控制台自带输入框（可当命令行用），不需要就关掉
ui.c.setColor("I", "#1de9b6");
ui.c.setColor("W", "#ff9800");
ui.c.setColor("E", "#f44336");
ui.c.setTextSize(13);

// ③ <globalconsole> 什么都不用写：它跟的是**全局**日志（本脚本 + 其它脚本 + 系统提示），
//    所以刚打印的这几条它也会有；区别是它不受本脚本生命周期影响。
ui.gc.setInputEnabled(false);

ui.log.on("click", function () {
    console.verbose("verbose：明细日志");
    console.log("log：普通日志");
    console.warn("warn：警告日志");
    console.error("error：错误日志 " + new Date().toLocaleString());
});

ui.clear.on("click", function () {
    ui.c.clear();    // 清掉上面那块（本脚本日志）
    ui.gc.clear();   // 清掉下面那块（全局日志）
});

console.log("两块控制台已就绪：上面那块跟本脚本，下面那块是全局日志");