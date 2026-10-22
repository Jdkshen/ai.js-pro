"ui";
"rhino";

var DotJumpLoading = require("./widgets/DotJumpLoading.js");

var PALETTES = [
    "#ff5f6d|#ffc371|#67e8f9|#a78bfa|#34d399",
    "#22d3ee|#60a5fa|#a78bfa|#f472b6|#fbbf24",
    "#ffffff|#ffffff|#ffffff|#ffffff|#ffffff"
];
var paletteIndex = 0;
var jumpHeightDp = 28;
var isPaused = false;

ui.layout(
    <frame background="#101218">
        <vertical padding="20" gravity="center">
            <text text="圆点接力跳动 · 自绘组件" textColor="#9aa3b8" textSize="13sp"
                  gravity="center" margin="0 0 0 18"/>

            <DotJumpLoading id="loadingClassic" w="240dp" h="110dp"
                            dotCount="5" dotRadius="7" dotSpacing="36"
                            jumpHeight="28" duration="520" dotColor="#ffffff"/>

            <DotJumpLoading id="loadingColor" w="240dp" h="110dp" marginTop="8"
                            dotColors="#ff5f6d|#ffc371|#67e8f9|#a78bfa|#34d399"
                            dotRadius="6" dotSpacing="34" jumpHeight="24" duration="560"/>

            <DotJumpLoading id="loadingAuto" marginTop="8"
                            jumpHeight="20" duration="700" dotColor="#7dd3fc"/>

            <text text="↑ 没写宽高：onMeasure 按内容自算" textColor="#5c6478" textSize="11sp" marginTop="4"/>

            <linear gravity="center" marginTop="24">
                <button id="pauseBtn" text="暂停" w="90dp" h="42dp"/>
                <button id="colorBtn" text="换色" w="90dp" h="42dp" marginLeft="10"/>
                <button id="sizeBtn" text="跳更高" w="90dp" h="42dp" marginLeft="10"/>
            </linear>
        </vertical>
    </frame>
);

ui.pauseBtn.click(function () {
    isPaused = !isPaused;
    var targets = [ui.loadingClassic, ui.loadingColor, ui.loadingAuto];
    for (var index = 0; index < targets.length; index++) {
        if (isPaused) {
            targets[index].stop();
        } else {
            targets[index].start();
        }
    }
    ui.pauseBtn.setText(isPaused ? "继续" : "暂停");
});

ui.colorBtn.click(function () {
    paletteIndex = (paletteIndex + 1) % PALETTES.length;
    ui.loadingColor.setColors(PALETTES[paletteIndex]);
});

ui.sizeBtn.click(function () {
    jumpHeightDp = jumpHeightDp >= 56 ? 16 : jumpHeightDp + 10;
    ui.loadingClassic.setJumpHeight(jumpHeightDp);
    ui.loadingAuto.setJumpHeight(Math.round(jumpHeightDp * 0.7));
});

ui.emitter.on("pause", function () {
    ui.loadingClassic.stop();
    ui.loadingColor.stop();
    ui.loadingAuto.stop();
});

// Returning from the background resumes only animations the user did not pause.
ui.emitter.on("resume", function () {
    if (!isPaused) {
        ui.loadingClassic.start();
        ui.loadingColor.start();
        ui.loadingAuto.start();
    }
});
