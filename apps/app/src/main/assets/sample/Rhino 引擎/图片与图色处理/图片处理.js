"ui";

function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

//项目仓库内的 AI.js Pro 图标（需联网，GitHub raw 链接）
var url = "https://github.com/Jdkshen/ai.js-pro/raw/HEAD/modules/autojs/src/main/res/drawable-nodpi/ai_js_pro_logo.png";
var logo = null;
var currentImg = null;

events.on("exit", function(){
    if(logo != null){
        logo.recycle();
    }
    if(currentImg != null){
        currentImg.recycle();
    }
});

ui.layout(
    <vertical>
        <img id="img" w="250" h="250" url="{{url}}" />
        <scroll>
            <vertical>
                <button id="rotate" text="旋转" />
                <button id="concat" text="拼接" />
                <button id="grayscale" text="灰度化" />
                <button id="binary" text="二值化" />
                <button id="adaptiveBinary" text="自适应二值化" />
                <button id="hsv" text="RGB转HSV" />
                <button id="blur" text="模糊" />
                <button id="medianBlur" text="中值滤波" />
                <button id="gaussianBlur" text="高斯模糊" />
            </vertical>
        </scroll>
    </vertical>
);

//把一张图片设置到图片控件中
function setImage(img) {
    ui.run(() => {
        ui.img.setImageBitmap(img.bitmap);
        var oldImg = currentImg;
        //不能立即回收currentImg，因为此时img控件还在使用它，应该在下次消息循环再回收它
        ui.post(()=>{
            if(oldImg != null){
                oldImg.recycle();
            }
        });
        currentImg = img;
    });
}

//启动一个处理图片的线程
var imgProcess = threads.start(function () {
    setInterval(() => { }, 1000);
});

//处理图片的函数，把任务交给图片处理线程处理
function processImg(name, process) {
    imgProcess.setTimeout(() => {
        if (logo == null) {
            var loadStartedAt = __nowMs();
            logo = images.load(url);
            console.log("[Rhino耗时] 下载并解码原图: " + __formatMs(__nowMs() - loadStartedAt) + " ms");
        }
        //处理图片
        var processStartedAt = __nowMs();
        var result = process(logo);
        console.log("[Rhino耗时] " + name + ": " + __formatMs(__nowMs() - processStartedAt) + " ms");
        //把处理后的图片设置到图片控件中
        setImage(result);
    }, 0);
}

var degress = 0;

ui.rotate.on("click", () => {
    processImg("旋转", img => {
        degress += 90;
        //旋转degress角度
        return images.rotate(img, degress);
    });
});

ui.concat.on("click", () => {
    processImg("拼接", img => {
        if(currentImg == null){
            toast("请先点击其他按钮，再点击本按钮");
            return img.clone();
        }
        //把currentImg拼接在img右边
        return images.concat(img, currentImg, "right");
    });
});

ui.grayscale.on("click", () => {
    processImg("灰度化", img => {
        //灰度化
        return images.grayscale(img);
    });
});

ui.binary.on("click", () => {
    processImg("二值化", img => {
        var g = images.grayscale(img);
        //二值化，取灰度为30到200之间的图片
        var result = images.threshold(g, 100, 200);
        g.recycle();
        return result;
    });
});

ui.adaptiveBinary.on("click", () => {
    processImg("自适应二值化", img => {
        var g = images.grayscale(img);
        //自适应二值化，最大值为200，块大小为25
        var result = images.adaptiveThreshold(g, 200, "MEAN_C", "BINARY", 25, 10);
        g.recycle();
        return result;
    });
});

ui.hsv.on("click", () => {
    processImg("RGB转HSV", img => {
        //RGB转HSV
        return images.cvtColor(img, "BGR2HSV");
    });
});

ui.blur.on("click", () => {
    processImg("模糊", img => {
        //模糊
        return images.blur(img, [10, 10]);
    });
});

ui.medianBlur.on("click", () => {
    processImg("中值滤波", img => {
        //中值滤波
        return images.medianBlur(img, 5);
    });
});

ui.gaussianBlur.on("click", () => {
    processImg("高斯模糊", img => {
        //高斯模糊
        return images.gaussianBlur(img, [5, 5]);
    });
});
