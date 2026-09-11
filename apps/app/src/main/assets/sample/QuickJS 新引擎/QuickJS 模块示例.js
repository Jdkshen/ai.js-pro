// @engine quickjs
// QuickJS 模块示例（被 require 引用）
// 用途：演示 QuickJS 的 CommonJS 模块导出写法，供其它样例 require 使用
// 前置：无（不单独运行）
// 覆盖：CommonJS

module.exports = { add: function (a, b) { return a + b; }, hello: 'QuickJS' };
