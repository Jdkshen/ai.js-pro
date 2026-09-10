"ui";

// 本地图片直接写 drawable 资源名（AI.js Pro 内置图标），无需联网
ui.layout(
<scroll>
    <vertical bg="#707070" padding="16">
        <text text="内置图片(drawable 资源名)" textColor="black" textSize="16sp" marginTop="16"/>
        <img src="ai_js_pro_logo"
            w="100" h="100"/>

        <text text="带边框的图片" textColor="black" textSize="16sp" marginTop="16"/>
        <img src="ic_android_eat_js"
                w="100" h="100" borderWidth="2dp" borderColor="#202020"/>

        <text text="圆形图片" textColor="black" textSize="16sp" marginTop="16"/>
        <img src="ic_android_eat_js"
                w="100" h="100" circle="true"/>

        <text text="带边框的圆形图片" textColor="black" textSize="16sp" marginTop="16"/>
        <img src="ic_android_eat_js"
                w="100" h="100" circle="true" borderWidth="2dp" borderColor="#202020"/>

        <text text="圆角图片" textColor="black" textSize="16sp" marginTop="16"/>
        <img id="rounded_img" src="ai_js_pro_logo"
                w="100" h="100" radius="20dp" scaleType="fitXY"/>
        <button id="change_img" text="更改图片"/>
    </vertical>
</scroll>
);

ui.change_img.on("click", ()=>{
    ui.rounded_img.setSource("ic_android_eat_js");
});