// @engine quickjs
"use strict";

// Material 3 风格令牌与布局。业务绑定在 page-settings / main，改版不改走棋逻辑。
const COLORS = { page: "#F7F9F3", panel: "#FFFFFF", panelAlt: "#EDF2E9", text: "#1B211C",
    muted: "#59645B", blue: "#386A4D", green: "#386A4D", red: "#A33835", amber: "#8B651D",
    primaryContainer: "#CEEBD7", onPrimaryContainer: "#173C27", outline: "#DCE5DA", errorContainer: "#FFEDEA" };
function build(version, palette) {
    const c = Object.assign({}, COLORS, palette || {});
    function text(value, size, color, extra) {
        return '<text text="' + value + '" textSize="' + (size || 14) + 'sp" textColor="' + (color || c.text) + '" ' + (extra || '') + '/>';
    }
    function button(id, label, weight, bg, fg, height) {
        const fill = bg || c.panelAlt;
        return '<card w="' + (weight ? '0dp' : 'match_parent') + '" h="' + (height || 48) + 'dp" ' +
            (weight ? 'layout_weight="1" ' : '') + 'margin="3dp" cardCornerRadius="24dp" cardElevation="0dp" cardBackgroundColor="' + fill + '" clipToOutline="true">' +
            '<button id="' + id + '" text="' + label + '" w="match_parent" h="match_parent" textSize="13sp" padding="2dp" minWidth="0dp" bg="' + fill + '" textColor="' + (fg || c.text) + '"/></card>';
    }
    function row(body) { return '<horizontal w="match_parent" gravity="center_vertical">' + body + '</horizontal>'; }
    function section(label, note, body, bg) {
        return '<card w="match_parent" marginBottom="12dp" cardCornerRadius="24dp" cardElevation="0dp" cardBackgroundColor="' + (bg || c.panel) + '"><vertical padding="16dp">' +
            text(label,18,c.text,'textStyle="bold" marginBottom="4dp"') +
            (note ? text(note,12,c.muted,'marginBottom="10dp"') : '') + body + '</vertical></card>';
    }
    function stepper(id, label, hint) {
        return row('<vertical w="0dp" layout_weight="1" paddingLeft="4dp">' + text(label,14) +
            text(hint,11,c.muted) + '</vertical><vertical w="48dp">' + button(id+'Minus','−',false) +
            '</vertical><text id="'+id+'Value" w="76dp" gravity="center" textSize="16sp" textStyle="bold" textColor="'+c.blue+'"/>' +
            '<vertical w="48dp">' + button(id+'Plus','+',false) + '</vertical>');
    }
    const modes = row(button('modeWatch','只识别',true)+button('modeAdvice','着法建议',true)+button('modeAuto','自动走棋',true));
    const state = text('对局控制',12,c.blue,'textStyle="bold" marginBottom="8dp"') +
        '<text id="statusIdle" text="准备就绪" textSize="26sp" textStyle="bold" textColor="'+c.text+'"/>' +
        '<text id="statusGood" textSize="26sp" textStyle="bold" textColor="'+c.green+'"/>' +
        '<text id="statusWarn" textSize="26sp" textStyle="bold" textColor="'+c.amber+'"/>' +
        '<text id="statusError" textSize="26sp" textStyle="bold" textColor="'+c.red+'"/>' +
        '<text id="status" textSize="13sp" textColor="'+c.muted+'" marginTop="6dp" marginBottom="12dp"/>' + modes +
        '<text id="modeHint" textSize="12sp" textColor="'+c.muted+'" marginTop="8dp"/>' +
        row(button('mySide','我方 · 红方',true)+button('assertTurn','现在轮到我',true));
    return '<vertical w="match_parent" h="match_parent" bg="'+c.page+'">' +
        '<horizontal padding="20dp 14dp" gravity="center_vertical"><card w="44dp" h="44dp" cardCornerRadius="16dp" cardElevation="0dp" cardBackgroundColor="'+c.primaryContainer+'">' +
        text('棋',23,c.onPrimaryContainer,'gravity="center" w="match_parent" h="match_parent" textStyle="bold"') +
        '</card><vertical layout_weight="1" marginLeft="12dp">'+text('象棋助手',23,c.text,'textStyle="bold"') +
        '<text id="version" text="对局工作台 · v'+version+'" textSize="11sp" textColor="'+c.muted+'"/></vertical></horizontal>' +
        '<scroll id="pageScroll" layout_weight="1" w="match_parent"><vertical padding="16dp 0dp">' +
        '<card cardCornerRadius="28dp" cardElevation="0dp" cardBackgroundColor="'+c.primaryContainer+'" marginBottom="12dp"><vertical padding="18dp">'+state+'</vertical></card>' +
        section('悬浮窗','进入游戏后，可在悬浮窗继续操作。',
            '<text id="floatyStatus" text="未打开" textSize="12sp" textColor="'+c.muted+'" marginBottom="6dp"/>' +
            row(button('floatyOpen','打开悬浮窗',true,c.blue,'#FFFFFF')+button('floatyClose','关闭',true)) +
            button('floatyCollapse','收起 / 展开悬浮窗')) +
        section('算棋设置','与悬浮窗共用，调整后下次算棋生效。',
            row(button('searchTime','限时搜索',true)+button('searchDepth','目标深度',true)) +
            stepper('think','思考预算','时间 / 目标层数') +
            row(button('thinkStep','步长 · 0.1 秒',true)+button('depthTimeout','深度超时 · 3 秒',true)) +
            button('engineToggle','引擎与策略  ▾') + '<vertical id="enginePanel">' +
            stepper('threads','引擎线程','1–8 个线程') + stepper('opening','开局库','我方前 0–12 手') +
            row(button('hashSize','缓存 · 128 MB',true)+button('contempt','避和 · 40',true)) +
            row(button('bookRandom','开局随机 · 开',true)+button('winSeek','求胜 · 关',true)) +
            button('strategyMenu','策略与战绩 · 特级大师') +
            text('较大的缓存会占用更多内存；避和与求胜可能增加走棋风险。',11,c.muted,'marginTop="8dp"') + '</vertical>') +
        section('棋盘工具','定位和实时识别会切到游戏，并复用悬浮窗的识别流程。',
            row(button('locate','定位棋盘',true)+button('recognizeLive','识别一帧',true)) +
            row(button('newSession','新会话',true)+button('openGame','打开游戏',true)) +
            button('pageDraw','着法标记 · 开')) +
        section('更多设置','截图、点击通道与离线图片工具。',
            button('advancedToggle','通道与图片工具  ▾')+'<vertical id="advancedPanel">'+
            button('captureChannel','截图来源 · 自动')+button('tapMethod','点击通道 · 自动')+
            text('离线图片路径（多图用逗号分隔）',12,c.muted,'marginTop="12dp"')+
            '<input id="image" text="/sdcard/Download/chess-shot-01.png" textSize="12sp" textColor="'+c.text+'"/>'+
            row(button('locateImage','定位图片',true)+button('recognizeImage','识别图片',true))+
            button('recognizeSeq','多图验证')+'</vertical>'+
            button('diagnosticToggle','诊断与运行详情  ▾')+'<vertical id="diagnosticPanel">'+
            row(button('selfcheck','离线自检',true)+button('contract','契约测试',true))+
            button('probe','运行环境')+'<text id="autoStatus" textSize="12sp" textColor="'+c.muted+'"/>'+
            '<text id="steps" textSize="11sp" textColor="'+c.muted+'"/></vertical>')+
        section('运行记录','最近一次操作的结果。',button('clearLog','清空记录')+
            '<text id="output" textSize="12sp" textColor="'+c.muted+'" text="暂无记录"/>')+
        '</vertical></scroll><vertical padding="16dp 8dp" bg="'+c.page+'">'+
        row(button('autoToggle','开始',true,c.blue,'#FFFFFF',54)+button('autoStop','停止',true,c.errorContainer,c.red,54))+
        text('先打开游戏棋盘，再开始；停止会同步到悬浮窗。',11,c.muted,'gravity="center" marginTop="4dp"')+
        '</vertical></vertical>';
}
module.exports = { build: build, COLORS: COLORS };
