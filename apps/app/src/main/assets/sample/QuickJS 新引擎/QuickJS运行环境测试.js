// @engine quickjs

const result = {
    engine: __engine__,
    chinese: "AI.js Pro 中文桥接正常",
    bigint: (2n ** 64n).toString(),
    optionalChaining: ({ nested: { value: 42 } })?.nested?.value,
    arrayResult: [1, 2, 3, 4].map(value => value * value)
};

console.log("QUICKJS_SMOKE_OK", result);
Promise.resolve("microtask").then(value => console.info("QUICKJS_PROMISE_OK", value));
toast("QuickJS 2026-06-04 运行成功");

result;
