"ui";

ui.layout(
    <vertical>
        <horizontal>
            <button id="add" text="插入 1 条" layout_weight="1" />
            <button id="remove" text="删除前 100 条" layout_weight="1" />
        </horizontal>
        <horizontal>
            <button id="update" text="更新首条" layout_weight="1" />
            <button id="scrollToStart" text="回到开头" layout_weight="1" />
            <button id="scrollToEnd" text="滑到末尾" layout_weight="1" />
        </horizontal>
        <list id="list" layout_weight="1">
            <card margin="8" cardBackgroundColor="#F0F3E8" cardCornerRadius="8"
                w="*" contentPadding="12">
                <horizontal gravity="center_vertical">
                    <vertical layout_weight="1">
                        <text textSize="16sp" textColor="#000000" text="名称：{{name}}" />
                        <text textSize="14sp" textColor="#666666" text="ID：{{id}}" />
                    </vertical>
                    <button id="deleteItem" text="删除" style="Widget.AppCompat.Button.Borderless" />
                </horizontal>
            </card>
        </list>
    </vertical>
);

var items = [];

// 当前引擎会观察数组的 push/splice 操作并刷新列表，不需要手动调用 Adapter。
ui.list.setDataSource(items);

ui.list.on("item_click", function (item) {
    toast("点击：" + item.name + "，ID=" + item.id);
});

ui.list.on("item_bind", function (itemView, itemHolder) {
    itemView.deleteItem.on("click", function () {
        items.splice(itemHolder.position, 1);
    });
});

ui.add.on("click", function () {
    var position = Math.min(5, items.length);
    items.splice(position, 0, { name: "新数据", id: -Date.now() });
});

ui.remove.on("click", function () {
    items.splice(0, Math.min(100, items.length));
});

ui.update.on("click", function () {
    if (!items.length) {
        return;
    }
    // 修改对象属性不会触发数组观察，因此用 splice 替换这一项。
    var first = items[0];
    items.splice(0, 1, { name: "已更新 " + new Date().toLocaleTimeString(), id: first.id + 1 });
});

ui.scrollToStart.on("click", function () {
    if (items.length) {
        ui.list.scrollToPosition(0);
    }
});

ui.scrollToEnd.on("click", function () {
    if (items.length) {
        ui.list.smoothScrollToPosition(items.length - 1);
    }
});

threads.start(function () {
    var data = [];
    for (var i = 0; i < 10000; i++) {
        data.push({ name: "第 " + i + " 项", id: i });
    }
    ui.run(function () {
        // 一次 push 完成批量插入，避免逐条跨线程刷新 UI。
        Array.prototype.push.apply(items, data);
    });
});
