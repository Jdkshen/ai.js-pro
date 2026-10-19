"quickjs";
// 原来的 canvas.on("draw", ...) 写法可直接使用，回调在 QuickJS 脚本线程执行。
var Paint = Java.type("android.graphics.Paint");
var paint = new Paint();
paint.setAntiAlias(true);
paint.setStrokeWidth(4);
paint.setTextSize(36);
var boardWindow = floaty.rawWindow(`
    <frame>
        <canvas id="board" w="*" h="*" />
        <button id="boardClose" text="关闭" w="80" h="48" layout_gravity="top|right" />
    </frame>
`);
boardWindow.setSize(Math.min(device.width - 40, 720), 550);
boardWindow.setPosition(20, 250);
boardWindow.exitOnClose();
boardWindow.boardClose.on("click", function() { boardWindow.close(); });
var board = boardWindow.board;
board.setMaxFps(30);
board.on("draw", function(canvas, view) {
    canvas.drawARGB(200, 20, 24, 32);
    paint.setColor(-16711936);
    var cx = canvas.getWidth() / 2;
    var cy = canvas.getHeight() / 2;
    canvas.drawLine(20, cy, canvas.getWidth() - 20, cy, paint);
    canvas.drawLine(cx, 80, cx, canvas.getHeight() - 20, paint);
    paint.setColor(-1);
    canvas.drawText("QuickJS Canvas", 20, 110, paint);
});
