// @engine quickjs

// AI.js Pro 图色助手：多点找色（基准点 100,602 → #3874CB，共 11 个点）
// 截图授权：无障碍优先（不弹授权），失败回退系统录屏授权
var __granted = false;
try { __granted = images.requestScreenCapture({ mode: 'accessibility' }); } catch (e) {}
if (!__granted) __granted = requestScreenCapture('portrait');
if (!__granted) throw new Error('截图授权失败');
var screen = captureScreen({ mode: 'full' });
var point = images.findMultiColors(screen, '#3874CB', [
    [9, 23, '#FFFFFF'],
    [32, 55, '#3874CB'],
    [21, 67, '#3874CB'],
    [-8, 67, '#3874CB'],
    [-17, 49, '#FFFFFF'],
    [-20, 35, '#FFFFFF'],
    [-20, 14, '#3874CB'],
    [32, 14, '#3874CB'],
    [41, 38, '#3874CB'],
    [41, 49, '#3874CB'],
], {
    region: [0, 502, 241, 267],
    threshold: 20
});
console.log(JSON.stringify(point));   // 命中 {x, y}，否则 null
screen.recycle();
