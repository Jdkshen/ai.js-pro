"nodejs";

const dialogs = require('dialogs');
const { buildDialog, showDialog } = dialogs;
const { viewFile } = require('app');
const { showToast } = require("toast");

const samples = [
    { title: '确认框与提示框', func: confirm },
    { title: '单选框', func: singleChoice },
    { title: '多选框', func: multiChoice },
    { title: '输入和进度条对话框', func: inputUrlAndDownload },
    { title: '悬浮对话框', func: floatingDialog },
];

async function main() {
    try {
        const position = await dialogs.showSelectDialog("请选择演示功能", samples.map(it => it.title));
        if (position >= 0) {
            await samples[position].func();
        } else {
            process.exit();
        }
    } catch (e) {
        console.error(e);
        process.exit();
    }
}

async function confirm() {
    const show = await dialogs.showConfirmDialog("是否要显示一个提示对话框？");
    if (show) {
        await dialogs.showAlertDialog("这是一个提示对话框");
    }
    process.exit();
}

async function singleChoice() {
    const items = ["男", "女", "其他", "保密"];
    const which = await dialogs.showSingleChoiceDialog("请选择性别", items, -1, {
        positive: "确定",
        negative: "取消",
    });
    showToast(`选择的是第${which + 1}个选项: ${items[which]}`);
    process.exit();
}

async function multiChoice() {
    (await showDialog({
        title: "喜欢的水果有",
        items: ["苹果🍎", "香蕉🍌", "菠萝🍍", "椰子🥥"],
        itemsSelectMode: 'multi',
        positive: "确定",
        negative: "取消"
    })).on("multi_choice", (indices, texts) => {
        showToast(`选择的选项是${indices}, 内容为${texts}`);
        process.exit();
    }).on('cancel', () => {
        showToast("选择取消");
        process.exit();
    });
}

async function inputUrlAndDownload() {
    const url = await dialogs.showInputDialog("请输入要下载的文件url", "https://picsum.photos/1920/1080");
    if (!url) {
        showToast("输入为空");
        process.exit();
        return;
    }
    const dialog = await showDialog({
        title: "文件下载中",
        progress: { max: 100, showMinMax: true },
        cancelable: false,
    });
    const date = new Date();
    const file = require('path').join(__dirname, `pic-${date.getTime()}.png`);
    downloadFile(url, file)
        .on("progress", (progress) => {
            dialog.setProgress(parseInt(progress * 100));
        })
        .on("success", (file) => {
            console.log(file);
            dialog.dismiss();
            showToast(`文件已下载到${file}`);
            viewFile(file);
            process.exit();
        })
        .on("error", (error) => {
            console.error(error);
            showToast(`下载失败: ${error}`);
            dialog.dismiss();
            process.exit();
        });
}

async function floatingDialog() {
    await showDialog({
        title: "悬浮窗对话框",
        content: "需要悬浮窗权限才能显示",
        type: 'overlay',
        positive: 'OK'
    });
    await dialogs.showMultiChoiceDialog("悬浮窗多选框", ["选项1", "选项2"], [], {
        type: 'overlay',
    });
}

function downloadFile(url, file) {
    const util = require('util');
    const stream = require('stream');
    const pipeline = util.promisify(stream.pipeline);
    const axios = require('axios').default;
    const fs = require('fs');
    const EventEmitter = require('node:events').EventEmitter;
    const emitter = new EventEmitter();

    (async () => {
        try {
            const response = await axios.get(url, {
                responseType: 'stream',
            });
            const totalSize = parseInt(response.headers['content-length']);
            let receivedSize = 0;
            await pipeline(response.data, new stream.Transform({
                transform(chunk, encoding, callback) {
                    receivedSize += chunk.length;
                    this.push(chunk);
                    callback();

                    const progress = typeof (totalSize) === 'number' && totalSize >= 0 ? receivedSize / totalSize : -1;
                    emitter.emit('progress', progress, receivedSize, totalSize);
                }
            }), fs.createWriteStream(file));
        } catch (e) {
            emitter.emit('error', e);
            return;
        }
        emitter.emit('success', file);
    })();

    return emitter;
}

main();

$autojs.keepRunning();