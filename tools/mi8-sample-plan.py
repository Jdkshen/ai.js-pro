#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the Xiaomi 8 (ce4d2bdb) execution plan for all bundled JS samples.

This tool is deliberately read-only with respect to product code and samples.
It reads the existing three-engine audit (inventory.json / sources.json /
sample-ledger.json) plus the sample-tree project.json files, classifies every
JS file as a runnable entry, interactive demo, long-running observation,
project dependency, blocked sample, or confirmation-gated device action, and
writes:

  output/mi8-three-engines-20261019/sample-plan.json
  output/mi8-three-engines-20261019/plan-notes.md

It never talks to a device and never edits samples or product files.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUDIT_DIR = ROOT / "output" / "three-engine-audit-20261019"
OUT_DIR = ROOT / "output" / "mi8-three-engines-20261019"
BASELINE_DIR = OUT_DIR / "baseline"
SAMPLE_DIR = ROOT / "apps" / "app" / "src" / "main" / "assets" / "sample"

LEDGER_PATH = AUDIT_DIR / "sample-ledger.json"
SOURCES_PATH = AUDIT_DIR / "sources.json"
INVENTORY_PATH = AUDIT_DIR / "inventory.json"

NODE = "Node 引擎/Node.js"
RHINO = "Rhino 引擎"
QJS = "QuickJS 引擎"

# ---------------------------------------------------------------------------
# Project manifests (project.json main fields), verified against the tree.
# ---------------------------------------------------------------------------

PROJECTS = [
    {
        "root": f"{NODE}/Node.js中使用UI/React",
        "engine": "node",
        "name": "React",
        "main": "dist/main.node.js",
        "source_entry": "main.node.js",
        "note": "project.json main 指向未随样例树提供的 dist/main.node.js；"
                "开发分支需 npm install/npm start，本任务禁止构建。",
    },
    {
        "root": f"{NODE}/Node.js中使用UI/Vue/Vue2+Vant(SFC)",
        "engine": "node",
        "name": "Vue2+Vant",
        "main": "main.node.js",
        "source_entry": "main.node.js",
        "note": "project.json 的 main 存在；但 WebView/dialogs/ui lifecycle 静态复核不完整。",
    },
    {
        "root": f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI",
        "engine": "node",
        "name": "Vue3+CLI",
        "main": "dist/main.node.js",
        "source_entry": "main.node.js",
        "note": "project.json main 指向未随样例树提供的 dist/main.node.js；"
                "开发分支需 npm install/npm run serve，本任务禁止构建。",
    },
    {
        "root": f"{NODE}/Node.js中使用UI/使用Android原生界面",
        "engine": "node",
        "name": "Node.js使用Android原生界面",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "必须按项目运行，project.json 提供 androidResources/res。",
    },
    {
        "root": f"{NODE}/Node.js中使用UI/简易文件浏览器",
        "engine": "node",
        "name": "FileExplorer",
        "main": "main.node.js",
        "source_entry": "main.node.js",
        "note": "project.json main 存在；WebView/jsBridge 有静态缺口。",
    },
    {
        "root": f"{NODE}/图片与颜色处理",
        "engine": "node",
        "name": "ImageProc",
        "main": "特征匹配(全分辨率找图).js",
        "source_entry": "特征匹配(全分辨率找图).js",
        "note": "project.json main 存在；同目录其它图色脚本仍可独立运行。",
    },
    {
        "root": f"{NODE}/脚本引擎与通信/Node.js启动Rhino引擎与通信",
        "engine": "node",
        "name": "Node.js ~ Rhino",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "main 启动 Rhino 子脚本 悬浮窗.js；Node24 engines API 有静态缺口。",
    },
    {
        "root": f"{NODE}/脚本引擎与通信/Rhino中启动Node.js代码与通信",
        "engine": "node",
        "name": "Rhino ~ Node.js",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "main 启动 http请求.node.js；Node24 engines API 有静态缺口。",
    },
    {
        "root": f"{QJS}/图色处理/SIFT特征匹配",
        "engine": "quickjs",
        "name": "SIFT特征匹配",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "main 使用 ./大图.png、./小图.png，并写 ./output/match_result.jpg。",
    },
    {
        "root": f"{QJS}/项目与打包/dsh 手机客户端",
        "engine": "quickjs",
        "name": "dsh 手机客户端 QuickJS",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "依赖同目录 layout.xml、client-runtime.jar；需要网络与服务端。",
    },
    {
        "root": f"{RHINO}/协程/ui中使用协程",
        "engine": "rhino",
        "name": "协程UI示例",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "project.json useFeatures=continuation，必须按项目运行。",
    },
    {
        "root": f"{RHINO}/协程/协程HelloWorld",
        "engine": "rhino",
        "name": "协程HelloWorld",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "project.json useFeatures=continuation，必须按项目运行。",
    },
    {
        "root": f"{RHINO}/图片与图色处理/找图",
        "engine": "rhino",
        "name": "ImageProc",
        "main": "特征匹配(全分辨率找图).js",
        "source_entry": "特征匹配(全分辨率找图).js",
        "note": "project.json main 存在；同目录找图脚本共用图片资源。",
    },
    {
        "root": f"{RHINO}/复杂界面/Vue2+Vant(SFC)",
        "engine": "rhino",
        "name": "Vue2+Vant",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "project.json main 存在；WebView 需要网络/页面资源。",
    },
    {
        "root": f"{RHINO}/复杂界面/使用Android原生界面",
        "engine": "rhino",
        "name": "使用Android原生界面",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "必须按项目运行，project.json 提供 androidResources/res。",
    },
    {
        "root": f"{RHINO}/项目与打包/悬浮控制",
        "engine": "rhino",
        "name": "悬浮控制",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "main 启动 worker.js 的悬浮控制条。",
    },
    {
        "root": f"{RHINO}/项目与打包/自定义ABI",
        "engine": "rhino",
        "name": "自定义ABI",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "ABI 裁剪特性需打包后验证；本任务禁止构建。",
    },
    {
        "root": f"{RHINO}/项目与打包/自定义启动图",
        "engine": "rhino",
        "name": "自定义启动图",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "splashLayoutXml 需打包后验证；本任务禁止构建。",
    },
    {
        "root": f"{RHINO}/项目与打包/自定义权限",
        "engine": "rhino",
        "name": "自定义权限",
        "main": "main.js",
        "source_entry": "main.js",
        "note": "manifestPermissions/requestListOnStartup 需打包后验证；本任务禁止构建。",
    },
]

# ---------------------------------------------------------------------------
# Support files: not independent entries.
# ---------------------------------------------------------------------------

SUPPORT = {
    f"{QJS}/QuickJS 模块示例.js": {
        "entry_kind": "module",
        "covered_by": f"{QJS}/新模块快速上手.js",
        "role": "被 require 的 CommonJS 模块（文件头明确“不单独运行”）",
    },
    f"{NODE}/Node.js中使用UI/React/src/App.js": {
        "entry_kind": "browser-entry",
        "covered_by": f"{NODE}/Node.js中使用UI/React/main.node.js",
        "role": "React 浏览器组件，由 CRA/webpack 打包进 dist/web",
    },
    f"{NODE}/Node.js中使用UI/React/src/App.test.js": {
        "entry_kind": "test-helper",
        "covered_by": f"{NODE}/Node.js中使用UI/React/main.node.js",
        "role": "Jest 测试，不是 Node24 入口",
    },
    f"{NODE}/Node.js中使用UI/React/src/index.js": {
        "entry_kind": "browser-entry",
        "covered_by": f"{NODE}/Node.js中使用UI/React/main.node.js",
        "role": "CRA 浏览器入口，由打包器消费",
    },
    f"{NODE}/Node.js中使用UI/React/src/reportWebVitals.js": {
        "entry_kind": "module",
        "covered_by": f"{NODE}/Node.js中使用UI/React/main.node.js",
        "role": "浏览器性能辅助模块",
    },
    f"{NODE}/Node.js中使用UI/React/src/setupTests.js": {
        "entry_kind": "test-helper",
        "covered_by": f"{NODE}/Node.js中使用UI/React/main.node.js",
        "role": "Jest 初始化，不是运行入口",
    },
    f"{NODE}/Node.js中使用UI/React/webpack.config.js": {
        "entry_kind": "build-config",
        "covered_by": f"{NODE}/Node.js中使用UI/React/main.node.js",
        "role": "构建期 webpack 配置",
    },
    f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI/babel.config.js": {
        "entry_kind": "build-config",
        "covered_by": f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI/main.node.js",
        "role": "构建期 Babel 配置",
    },
    f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI/src/main.js": {
        "entry_kind": "browser-entry",
        "covered_by": f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI/main.node.js",
        "role": "Vue 浏览器入口，由 @vue/cli-service 打包",
    },
    f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI/vue.config.js": {
        "entry_kind": "build-config",
        "covered_by": f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI/main.node.js",
        "role": "构建期 Vue CLI 配置",
    },
    f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI/webpack.config.js": {
        "entry_kind": "build-config",
        "covered_by": f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI/main.node.js",
        "role": "构建期 webpack 配置",
    },
    f"{NODE}/脚本引擎与通信/Node.js启动Rhino引擎与通信/悬浮窗.js": {
        "entry_kind": "child-script",
        "covered_by": f"{NODE}/脚本引擎与通信/Node.js启动Rhino引擎与通信/main.js",
        "role": "Rhino 悬浮窗子脚本，由 Node main.js 启动；直接运行会提示退出",
    },
    f"{NODE}/脚本引擎与通信/Rhino中启动Node.js代码与通信/http请求.node.js": {
        "entry_kind": "child-script",
        "covered_by": f"{NODE}/脚本引擎与通信/Rhino中启动Node.js代码与通信/main.js",
        "role": "Node 子脚本，由 Rhino main.js 启动；直接运行会提示退出",
    },
    f"{RHINO}/项目与打包/悬浮控制/worker.js": {
        "entry_kind": "child-script",
        "covered_by": f"{RHINO}/项目与打包/悬浮控制/main.js",
        "role": "悬浮控制条启动的 worker 循环脚本",
    },
    f"{RHINO}/悬浮窗/test.js": {
        "entry_kind": "test-helper",
        "covered_by": f"{RHINO}/悬浮窗/悬浮运行脚本按钮.js",
        "role": "被“悬浮运行脚本按钮”启动的测试载荷（无限 toast）",
    },
    f"{RHINO}/脚本引擎/被运行的脚本文件.js": {
        "entry_kind": "test-helper",
        "covered_by": f"{RHINO}/脚本引擎/运行脚本文件.js",
        "role": "被 engines.execScriptFile 启动的测试载荷",
    },
    f"{RHINO}/界面控件/自定义控件-模块-配置勾选框.js": {
        "entry_kind": "module",
        "covered_by": f"{RHINO}/界面控件/自定义控件-使用配置勾选框.js",
        "role": "自定义控件实现模块，顶层使用 ui.Widget，不能独立运行",
    },
}

# ---------------------------------------------------------------------------
# Blocked samples: a hard prerequisite is absent or the task forbids the action.
# ---------------------------------------------------------------------------

BLOCKED = {
    f"{NODE}/Node.js中使用UI/React/main.node.js": {
        "missing": [
            "dist/main.node.js（project.json main，样例树未提供）",
            "node_modules / npm install（本任务禁止构建）",
            "Node24 ui/engines/lang API 缺口（静态复核）",
        ],
        "reason": "React 项目入口缺构建产物；开发分支会执行 npm install/npm start，"
                  "既违背后台禁止构建，也无法保证 5 分钟内完成；即使构建，"
                  "ui/engines/lang 仍有静态缺口。",
    },
    f"{NODE}/Node.js中使用UI/Vue/Vue3+CLI/main.node.js": {
        "missing": [
            "dist/main.node.js（project.json main，样例树未提供）",
            "node_modules / npm install（本任务禁止构建）",
            "Node24 ui/engines/lang API 缺口（静态复核）",
        ],
        "reason": "Vue3 项目入口缺构建产物；开发分支会执行 npm install/npm run serve，"
                  "本任务禁止构建；即使构建，ui/engines/lang 仍有静态缺口。",
    },
    f"{NODE}/文字识别(MLKitOCR).js": {
        "missing": [
            "官方 MLKitOCR 插件 org.autojs.autojspro.plugin.mlkit.ocr",
            "Node24 的 plugins 模块（静态复核缺失）",
        ],
        "reason": "Node 版 OCR 需先装官方插件；静态复核也确认 plugins 模块未注册。",
    },
    f"{NODE}/文字识别(PaddleOCR).js": {
        "missing": [
            "官方 OCR 插件/模型（本机未确认安装）",
            "Node24 的 ocr 模块（静态复核缺失）",
        ],
        "reason": "Node 版 PaddleOCR 依赖官方插件与 ocr 模块；两者当前都不可用。",
    },
    f"{NODE}/插件.js": {
        "missing": [
            "ffmpeg 插件 org.autojs.plugin.ffmpeg",
            "Node24 的 plugins 模块（静态复核缺失）",
        ],
        "reason": "示例加载 ffmpeg 插件；本机插件与 Node24 plugins 模块均未确认。",
    },
    f"{RHINO}/文字识别(OCR)/截图并识别文字(MLKitOCR).js": {
        "missing": ["官方 MLKitOCR 插件"],
        "reason": "Rhino 版 OCR 通过 $plugins.load 加载官方插件，未安装则直接失败。",
    },
    f"{RHINO}/文字识别(OCR)/截图并识别文字(PaddleOCR).js": {
        "missing": ["官方 PaddleOCR/OCR 插件与模型"],
        "reason": "Rhino 版 PaddleOCR 依赖插件商店中的 OCR 插件与模型。",
    },
    f"{RHINO}/任务/视频文件提取mp3文件.js": {
        "missing": ["ffmpeg 插件", "外部 video/* Intent"],
        "reason": "直接运行只会登记外部 Intent 任务；真正提取还需 ffmpeg 插件和用户选择的视频。",
    },
    f"{RHINO}/脚本引擎/运行录制文件.js": {
        "missing": ["./test.auto（样例树中不存在）"],
        "reason": "engines.execAutoFile 指向的录制文件没有随样例分发。",
    },
    f"{RHINO}/悬浮窗/悬浮窗运行脚本按钮简单版.js": {
        "missing": ["/sdcard/脚本/test.js（运行时缺失）"],
        "reason": "脚本启动前检查硬编码路径 /sdcard/脚本/test.js，缺失即退出。",
    },
    f"{QJS}/悬浮窗/悬浮窗运行脚本按钮简单版.js": {
        "missing": ["/sdcard/脚本/test.js（运行时缺失）"],
        "reason": "脚本启动前检查硬编码路径 /sdcard/脚本/test.js，缺失即退出。",
    },
    f"{QJS}/悬浮窗/悬浮运行脚本按钮.js": {
        "missing": ["/sdcard/脚本/test.js（运行时缺失）"],
        "reason": "脚本启动前检查硬编码路径 /sdcard/脚本/test.js，缺失即退出。",
    },
    f"{QJS}/YOLO目标检测/DNN/YOLO姿态识别.js": {
        "missing": ["姿态 end2end ONNX 模型（模型库当前只有 yolo26_160 检测模型）"],
        "reason": "姿态输出需要 6+3K 行宽的姿态模型；内置模型不是姿态模型。",
    },
    f"{QJS}/YOLO目标检测/DNN/YOLO姿态实时绘制.js": {
        "missing": ["姿态 end2end ONNX 模型（模型库当前只有 yolo26_160 检测模型）"],
        "reason": "姿态绘制需要姿态模型；没有模型时只能得到检测框或空关键点。",
    },
}

# ---------------------------------------------------------------------------
# Confirmation-gated samples. The value is the concrete device/data risk.
# ---------------------------------------------------------------------------

CONFIRM = {
    f"{RHINO}/Shell命令/结束所有后台进程.js":
        "执行 am kill-all，会结束其它应用的后台进程。",
    f"{RHINO}/应用/卸载应用.js":
        "按用户输入的应用包名调用 app.uninstall，可能卸载用户应用。",
    f"{RHINO}/应用/应用工具.js":
        "菜单包含 app.uninstall；用户可选中卸载分支。",
    f"{RHINO}/应用/强制停止应用.js":
        "打开应用详情页并循环 click('强制停止')，会改变用户应用状态。",
    f"{RHINO}/应用/自动重命名所有apk文件.js":
        "递归扫描内置存储并批量重命名所有 APK，无确认/空跑开关。",
    f"{RHINO}/脚本引擎/停止所有正在运行的脚本.js":
        "engines.stopAllAndToast() 会结束所有正在运行的脚本，包括其它任务。",
    f"{RHINO}/文件读写/删除所有空文件夹.js":
        "用户确认后递归删除 /sdcard 下所有空目录。",
    f"{RHINO}/设置/设置.js":
        "持久修改 Auto.js Pro 的 stable_mode 与 foreground_service 设置。",
    f"{RHINO}/任务/定时任务-一次性任务.js":
        "新增一次性定时任务，指向 /sdcard/脚本/定时任务-一次性任务.js。",
    f"{RHINO}/任务/定时任务-广播任务.js":
        "新增 BATTERY_CHANGED 广播任务，指向 /sdcard/脚本/电量变化时.js。",
    f"{RHINO}/任务/定时任务-每10分钟运行.js":
        "新增自我续期的 10 分钟定时任务，会持续存在。",
    f"{RHINO}/任务/定时任务-每周任务.js":
        "新增每周任务（日/一/三/五 13:00），指向 /sdcard/脚本/定时任务-每周.js。",
    f"{RHINO}/任务/定时任务-每日任务.js":
        "新增每日 10:00 任务，指向 /sdcard/脚本/每日任务.js。",
    f"{RHINO}/安卓7.0+点按和手势/RootAutomator2.js":
        "以 Root 注入真实点击/长按/滑动/双指捏合，作用于当前前台应用。",
    f"{RHINO}/安卓7.0+点按和手势/RootAutomator双指捏合.js":
        "以 Root 注入 350 步双指捏合，作用于当前前台应用。",
    f"{RHINO}/安卓7.0+点按和手势/三指下滑.js":
        "向当前前台应用注入三指下滑手势。",
    f"{RHINO}/安卓7.0+点按和手势/三指捏合.js":
        "先按 Home 再向桌面注入三指捏合，可能触发启动器编辑状态。",
    f"{RHINO}/安卓7.0+点按和手势/双指捏合.js":
        "先按 Home 再向桌面注入双指捏合，可能触发启动器编辑状态。",
    f"{RHINO}/安卓7.0+点按和手势/心形手势.js":
        "向当前前台应用注入一条心形手势轨迹。",
    f"{RHINO}/安卓7.0+点按和手势/拉出通知栏.js":
        "从状态栏位置下滑，会拉出系统通知栏。",
    f"{RHINO}/安卓7.0+点按和手势/点击左上角.js":
        "在当前前台应用左上角注入真实点击。",
    f"{RHINO}/图片与图色处理/截图并保存.js":
        "覆盖固定路径 /sdcard/1.png。",
    f"{RHINO}/图片与图色处理/找图/找出问号方块并画出位置.js":
        "覆盖 /sdcard/tmp.png 并打开图片查看器。",
    f"{RHINO}/HTTP网络请求/文件下载.js":
        "覆盖固定路径 /sdcard/1.png 并打开图片查看器。",
    f"{RHINO}/文件读写/写入文本文件.js":
        "以写模式覆盖固定路径 /sdcard/1.txt。",
    f"{RHINO}/文件读写/读写文本文件.js":
        "覆盖并追加固定路径 /sdcard/1.txt。",
    f"{RHINO}/文件读写/文件编码转换.js":
        "读取 /sdcard/1.txt 并覆盖 /sdcard/2.txt。",
    f"{RHINO}/文件读写/文件编码转换(高级).js":
        "读取 /sdcard/1.txt 并覆盖 /sdcard/2.txt。",
    f"{RHINO}/控制台/保存日志.js":
        "把全局日志写到 /sdcard/log.txt 并打开文件。",
    f"{QJS}/系统与设备/QuickJS 本地存储与全局动作.js":
        "注入 press/longClick 并执行 recents()+back，会离开当前界面。",
    f"{QJS}/系统与设备/QuickJS Shizuku Shell 点击.js":
        "通过 Shizuku 向当前屏幕坐标 (500,800) 注入真实点击。",
    f"{QJS}/系统与设备/QuickJS Shizuku Shell 自动滑动.js":
        "通过 Shizuku 连续注入 10 次上滑手势，作用于当前前台应用。",
    f"{QJS}/触控/Root 多点触控.js":
        "用户确认后以 Root 注入双指缩放/多指触摸。",
    f"{QJS}/触控/Shizuku 单指触控.js":
        "用户确认后以 Shizuku 注入一次单指上滑。",
    f"{NODE}/root-automator2.js":
        "以 Root 注入点击/长按/滑动/双指捏合，作用于当前前台应用。",
    f"{NODE}/shell命令执行.js":
        "Root 可用时执行 Tap(100,100)，向当前前台应用注入点击；还会执行 root shell。",
    f"{NODE}/任务.js":
        "新增每日/每周/广播任务，只删除 task1；其余任务与硬编码的 /sdcard/脚本/* "
        "路径会残留（样例树没有这些脚本）。",
    f"{NODE}/设置.js":
        "持久修改 stable_mode / foreground_service 设置。",
    f"{NODE}/无障碍/坐标点击与滑动.js":
        "向当前前台应用注入点击、滑动、长按、按压与手势。",
    f"{NODE}/无障碍/无障碍使用示例.js":
        "点击当前界面第一个 clickable 控件。",
    f"{NODE}/无障碍/模拟按键与其他动作.js":
        "切换最近任务、返回、Home、通知栏，并在 Android 9+ 锁屏。",
    f"{NODE}/无障碍/选择器.js":
        "点击当前界面坐标与匹配控件，可能误触任意前台应用。",
    f"{NODE}/无障碍/使用无障碍权限截图.js":
        "覆盖 /sdcard/test.png 并打开图片查看器。",
    f"{NODE}/电量优化与设备唤醒.js":
        "请求忽略电池优化、自动锁屏并唤醒屏幕。",
    f"{NODE}/播放音乐.js":
        "下载并覆盖 /sdcard/脚本/HollowKnight.mp3，随后外放音乐约 20 秒。",
}

# ---------------------------------------------------------------------------
# Mode sets for interactive and observe. Rhino "ui" scripts are detected from
# their source directive in addition to these explicit sets.
# ---------------------------------------------------------------------------

INTERACTIVE = {
    # Rhino dialogs / apps
    f"{RHINO}/应用/打开应用.js",
    f"{RHINO}/应用/发送意图-文本消息分享.js",
    f"{RHINO}/任务/读取文本文件.js",
    f"{RHINO}/图片与图色处理/颜色获取和检测.js",
    f"{RHINO}/图片与图色处理/找到QQ红点位置.js",
    f"{RHINO}/悬浮窗/动态悬浮文字.js",
    f"{RHINO}/悬浮窗/悬浮文字.js",
    f"{RHINO}/悬浮窗/悬浮窗输入框.js",
    f"{RHINO}/悬浮窗/悬浮运行脚本按钮.js",
    # QuickJS UI / dialog / target-app interaction
    f"{QJS}/Java 互操作/02-Intent 与系统服务.js",
    f"{QJS}/系统与设备/QuickJS 通知监听与全局动作.js",
    f"{QJS}/界面与交互/QuickJS Dialogs 交互示例.js",
    f"{QJS}/界面与交互/QuickJS Dialogs 测试.js",
    f"{QJS}/界面与交互/QuickJS UI 控件代理与事件.js",
    f"{QJS}/界面与交互/自定义触摸与无障碍点击.js",
    f"{QJS}/悬浮窗/悬浮文字.js",
    f"{QJS}/悬浮窗/动态悬浮文字.js",
    f"{QJS}/悬浮窗/悬浮窗输入框.js",
    f"{QJS}/画布/悬浮画布.js",
    f"{QJS}/YOLO目标检测/模型管理.js",
    f"{QJS}/图像与视觉/QuickJS 象棋棋盘分析.js",
    f"{QJS}/图色处理/11-窗口截图与ImGui绘制.js",
    f"{QJS}/图色处理/12-ImGui操作窗.js",
    f"{QJS}/引擎与线程/QuickJS 系统事件监听测试.js",
    f"{QJS}/项目与打包/dsh 手机客户端/main.js",
    # Node UI / dialog / floaty main
    f"{NODE}/Node.js与Android和Java交互/继承Java类-自定义View.js",
    f"{NODE}/Node.js中使用UI/Vue/Vue2+Vant(SFC)/main.node.js",
    f"{NODE}/Node.js中使用UI/传统界面/main.node.js",
    f"{NODE}/Node.js中使用UI/使用Android原生界面/main.js",
    f"{NODE}/Node.js中使用UI/简易文件浏览器/main.node.js",
    f"{NODE}/Node.js中使用UI/自定义组件/colored-button.js",
    f"{NODE}/对话框.js",
    f"{NODE}/脚本引擎与通信/Node.js启动Rhino引擎与通信/main.js",
}

OBSERVE = {
    f"{RHINO}/事件与按键、触摸监听/Toast监听.js",
    f"{RHINO}/事件与按键、触摸监听/按键监听.js",
    f"{RHINO}/事件与按键、触摸监听/触摸监听.js",
    f"{RHINO}/事件与按键、触摸监听/通知监听.js",
    f"{RHINO}/事件与按键、触摸监听/长按返回退出当前程序.js",
    f"{RHINO}/事件与按键、触摸监听/音量键控制程序.js",
    f"{RHINO}/任务/定时任务-查询与取消.js",
    f"{RHINO}/多线程/多线程按键监听.js",
    f"{RHINO}/图片与图色处理/实时显示触摸点颜色.js",
    f"{RHINO}/图片与图色处理/找图/异步截图.js",
    f"{RHINO}/悬浮窗/护眼模式.js",
    f"{QJS}/系统与设备/指纹手势.js",
    f"{QJS}/YOLO目标检测/DNN/YOLO区域检测.js",
    f"{QJS}/YOLO目标检测/DNN/YOLO持续识别.js",
    f"{QJS}/YOLO目标检测/DNN/截图自动切换测试.js",
    f"{QJS}/图色处理/08-图色模板长时间稳定性测试.js",
    f"{NODE}/发送通知.js",
    f"{NODE}/通知与Toast监听.js",
    f"{NODE}/无障碍/按键监听.js",
    f"{NODE}/无障碍/无障碍事件.js",
    f"{NODE}/图片与颜色处理/截图与找图.js",
    f"{NODE}/使用Node.js模块/http-server-helloworld.js",
    f"{NODE}/Node.js中使用UI/悬浮窗/悬浮日志.node.js",
}

# ---------------------------------------------------------------------------
# Timeouts (seconds). 0 means "not executed in this plan".
# ---------------------------------------------------------------------------

TIMEOUT_OVERRIDES = {
    f"{QJS}/YOLO目标检测/DNN/YOLO区域检测.js": 600,
    f"{QJS}/YOLO目标检测/DNN/YOLO持续识别.js": 600,
    f"{QJS}/图色处理/01-图色 API 自动回归.js": 120,
    f"{QJS}/图色处理/02-截图找色与多点找色.js": 120,
    f"{QJS}/图色处理/04-模板匹配.js": 120,
    f"{QJS}/图色处理/05-全分辨率与视觉加速对比.js": 120,
    f"{QJS}/图色处理/08-图色模板长时间稳定性测试.js": 600,
    f"{QJS}/YOLO目标检测/DNN/截图自动切换测试.js": 240,
    f"{QJS}/回归测试/QuickJS 全模块回归测试.js": 300,
    f"{QJS}/系统与设备/指纹手势.js": 45,
    f"{QJS}/项目与打包/dsh 手机客户端/main.js": 180,
    f"{QJS}/文件与网络/WebSocket.js": 30,
    f"{NODE}/使用Node.js模块/http-server-helloworld.js": 30,
    f"{NODE}/播放音乐.js": 180,
    f"{NODE}/Node.js中使用UI/悬浮窗/悬浮日志.node.js": 180,
    f"{RHINO}/悬浮窗/护眼模式.js": 75,
    f"{RHINO}/传感器/打印常用传感器信息.js": 45,
    f"{RHINO}/多线程/多线程简单示例.js": 45,
    f"{RHINO}/定时器/定时执行.js": 35,
    f"{RHINO}/WebSocket/WebSocket.js": 30,
    f"{RHINO}/画布/贪吃蛇.js": 180,
    f"{RHINO}/画布/贪吃蛇重力感应版.js": 300,
    f"{RHINO}/界面控件/表格控件-内置图标查看器.js": 180,
}

DEFAULT_TIMEOUT = {
    "run": 60,
    "interactive": 180,
    "observe": 120,
    "requires_confirmation": 120,
    "blocked": 0,
    "support": 0,
}

RUN_NOTES = {
    f"{QJS}/系统与设备/自动重命名所有 APK 文件.js":
        "当前 RENAME=false，只扫描并打印待重命名项；不得改成 true。",
    f"{QJS}/文件与网络/SQLite 数据库增删改查.js":
        "只重建/删除 /sdcard/quickjs-sqlite-demo.db 这个演示库。",
    f"{QJS}/文件与网络/文件夹变更监听.js":
        "只创建/删除 /sdcard/quickjs-watch-demo 下的测试文件。",
    f"{QJS}/文件与网络/文件夹变更监听（轮询）.js":
        "只操作 /sdcard/quickjs-watch-demo 下的测试文件，并观察 10 秒。",
    f"{RHINO}/文件读写/文件（夹）监听.js":
        "只创建/删除 /sdcard/脚本/_test_for_file_observer.txt。",
    f"{RHINO}/Zip压缩与解压/Zip文件压缩与解压.js":
        "只操作 /sdcard/脚本/zip_test 与 zip_out 演示目录。",
    f"{NODE}/zip压缩与解压.js":
        "只操作 /sdcard/脚本/zip_test 与 zip_out 演示目录。",
    f"{NODE}/使用Node.js模块/axios网络请求.js":
        "会在 cwd 写入 qq.com.html（样例目录，不是用户目录）。",
    f"{NODE}/剪贴板.js":
        "会覆盖系统剪贴板，原内容不保留；执行前记录。",
    f"{NODE}/设备与设备信息.js":
        "会振动约 4 秒（脚本 2 秒后取消）。",
    f"{NODE}/Node.js与Android和Java交互/继承Java类-SQLite数据库.js":
        "会在 cwd 创建/写入 data.db 并插入 10 行。",
    f"{RHINO}/数据库/数据库事务.js":
        "依赖先运行同目录“数据库增删改查”生成 ./data.db。",
    f"{RHINO}/HTTP网络请求/文件上传.js":
        "只读取 /sdcard/1.txt；外部 posttestserver 失败也按网络结果记录。",
}

# Large sources that were reviewed by head/tail plus keyword scan rather than
# line-by-line. Everything else was read in full by the classifier.
TARGETED_REVIEW = {
    f"{RHINO}/画布/贪吃蛇重力感应版.js",
    f"{QJS}/回归测试/QuickJS 全模块回归测试.js",
    f"{QJS}/YOLO目标检测/模型管理.js",
    f"{RHINO}/界面控件/表格控件-内置图标查看器.js",
    f"{QJS}/YOLO目标检测/DNN/YOLO持续识别.js",
    f"{QJS}/YOLO目标检测/DNN/YOLO姿态实时绘制.js",
    f"{QJS}/图色处理/08-图色模板长时间稳定性测试.js",
    f"{QJS}/YOLO目标检测/DNN/YOLO区域检测.js",
}

ENGINE_ORDER = {"rhino": 0, "quickjs": 1, "node": 2, "unknown": 3}
MODE_ORDER = {
    "run": 1,
    "interactive": 2,
    "observe": 3,
    "requires_confirmation": 4,
    "blocked": 0,
    "support": 0,
}

SCREENSHOT_RE = re.compile(
    r"(requestScreenCapture|captureScreen|media_projection|images\.captureScreen|"
    r"images\.requestScreenCapture|takeScreenshot|isWindowCaptureAvailable)"
)
ACCESSIBILITY_RE = re.compile(
    r"(accessibility\.|auto\(\s*\)|auto\.waitFor|events\.observe(?:Key|Touch|Notification|Toast|Gesture)|"
    r"\bback\(\)|\bhome\(\)|\brecents\(\)|\bnotifications\(\)|\bquickSettings\(\)|"
    r"\bclick\(|\bswipe\(|\bgesture)")
ROOT_RE = re.compile(
    r"(new\s+RootAutomator|RootAutomator2|createRootAutomator2|backend:\s*[\"']root[\"']|"
    r"touch\.create\(\{backend:\s*[\"']root[\"']|\bsu\b|root:\s*true)"
)
SHIZUKU_RE = re.compile(r"\bshizuku\b")
NETWORK_RE = re.compile(r"(https?://|wss?://|require\([\"']axios[\"']|require\([\"']http)")
PLUGIN_RE = re.compile(r"(plugins\.load|\$plugins\.load|require\([\"']ocr[\"']|createOCR)")
DIALOG_RE = re.compile(
    r"(dialogs\.|alert\s*\(|confirm\s*\(|rawInput\s*\(|prompt\s*\(|showDialog\s*\(|"
    r"showConfirmDialog|showInputDialog|showSelectDialog|dialogs\.select|dialog\.show)"
)
FLOATY_RE = re.compile(r"(floaty\.|createWindow|floating_window|\$floaty|setContentView)")
UI_RE = re.compile(r"(\"ui\"|'ui'|ui\.|ui\.layout|\$ui\.|\bui\.Activity|uiMode)")

STATIC_FAIL = {"fail", "static_blocked"}

# The audit's static conclusion for this one is contradicted by the current
# source: the code uses an explicit https:// URL, while the ledger says the
# scheme is missing. Keep the audit text for traceability but do not predict a
# failure from it.
STATIC_OVERRIDES = {
    f"{RHINO}/HTTP网络请求/获取网页.js": {
        "risk": "partial",
        "confidence": "low",
        "note": "ledger 结论称 URL 缺 scheme，但当前源码为 https://www.baidu.com；"
                "该静态结论与源码不符，按 partial 处理，以真机结果为准。",
    },
}


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def load_existing_baseline():
    """Read the primary's already-existing partial device baseline, if present.

    These are real device results from a previous/parallel run. This task did
    not produce them and must not count them as its own coverage; they are
    attached for comparison and de-duplication.
    """
    rows = {}
    if not BASELINE_DIR.is_dir():
        return rows
    for file in sorted(BASELINE_DIR.glob("*.json")):
        try:
            data = read_json(file)
        except (OSError, ValueError):
            continue
        path = npath(data.get("path", ""))
        if not path:
            continue
        rows[path] = {
            "index": data.get("index"),
            "status": data.get("status"),
            "functionalPass": data.get("functionalPass"),
            "elapsedSeconds": data.get("elapsedSeconds"),
            "errors": (data.get("errors") or [])[:3],
            "evidence": data.get("evidence"),
            "sourceSha256": data.get("sourceSha256"),
            "origin": "existing output/mi8-three-engines-20261019/baseline（非本轮）",
        }
    return rows


def code_view(source: str) -> str:
    """Drop comment-only lines and block comments before keyword tagging.

    Documentation URLs in comments otherwise make every third sample look like
    a network sample and pollute the execution reasons.
    """
    text = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    lines = []
    for line in text.splitlines():
        if line.lstrip().startswith("//"):
            continue
        lines.append(line)
    return "\n".join(lines)


def npath(path: str) -> str:
    return path.replace("\\", "/")


def effective_engine(path: str, source: str) -> str:
    """Read the engine directive from the first few lines.

    One audit row (`Node.js启动Rhino引擎与通信/悬浮窗.js`) is declared as Node
    but is actually a Rhino child script. Keep the declared engine separately.
    """
    head = "\n".join(source.splitlines()[:8])
    if re.search(r"^\s*[\"']quickjs[\"']\s*;", head, re.M):
        return "quickjs"
    if re.search(r"^\s*[\"']rhino[\"']\s*;", head, re.M):
        return "rhino"
    if re.search(r"^\s*[\"']nodejs[\"']\s*;", head, re.M):
        return "node"
    if re.search(r"^\s*[\"']auto[\"']\s*;", head, re.M):
        return "rhino"
    return "unknown"


def project_for(path: str):
    best = None
    for project in PROJECTS:
        root = project["root"]
        if path == root or path.startswith(root + "/"):
            if best is None or len(root) > len(best["root"]):
                best = project
    return best


def first_sentence(text: str, limit: int = 220) -> str:
    text = " ".join((text or "").split())
    if not text:
        return ""
    match = re.search(r"(?<=[.!?。])\s", text)
    if match and match.start() < limit:
        return text[: match.start() + 1]
    return text[:limit].rstrip(" ,;") + ("…" if len(text) > limit else "")


def extract_markers(source: str):
    pattern = re.compile(
        r"(?:console\.(?:log|info|warn|error)|toastLog|toast|log|print)\(\s*"
        r"([\"'])(.{3,120}?)\1"
    )
    seen = []
    for _, text in pattern.findall(source):
        text = text.replace("\\n", " ").strip()
        if text and text not in seen:
            seen.append(text)
    success_keys = ("OK", "DONE", "===", "完成", "通过", "成功", "PASS",
                    "匹配总耗时", "检测结果", "帧数", "耗时")
    good = [text for text in seen if any(key in text for key in success_keys)]
    if good:
        chosen = good[-3:]
    else:
        strong = [
            text for text in seen
            if any(key in text for key in ("OK", "DONE", "===", "完成", "通过",
                                           "失败", "错误"))
        ]
        chosen = (strong or seen)[-3:]
    return chosen


def has_ui_directive(source: str) -> bool:
    for line in source.splitlines()[:4]:
        if re.fullmatch(r"\s*[\"']ui[\"']\s*;?\s*", line):
            return True
    return False


def tags_for(path: str, source: str):
    code = code_view(source)
    tags = []
    if has_ui_directive(source) or UI_RE.search(code):
        tags.append("ui")
    if DIALOG_RE.search(code):
        tags.append("dialog")
    if FLOATY_RE.search(code):
        tags.append("floaty")
    if SCREENSHOT_RE.search(code):
        tags.append("screenshot")
    if re.search(r"\$ocr|文字识别|OCR", path + code):
        tags.append("ocr")
    if "YOLO" in path:
        tags.append("yolo")
    if NETWORK_RE.search(code):
        tags.append("network")
    if re.search(r"(files\.|open\(|readImage|writeImage|\$files\.)", code):
        tags.append("files")
    if re.search(r"(sqlite|SQLite|openDatabase|datastore|storages\.)", code):
        tags.append("storage")
    if re.search(r"(\$work_manager|workManager|addDailyTask|addWeeklyTask|addDisposableTask)", code):
        tags.append("tasks")
    if re.search(r"(\$settings|settings\.)", code):
        tags.append("settings")
    if re.search(r"(require\([\"']engines[\"']|engines\.|threads\.|\$engines)", code):
        tags.append("engine")
    if re.search(r"(threads\.|workers?|worker)", code, re.I):
        tags.append("threads")
    if re.search(r"(crypto|Cipher|MessageDigest|md5|sha256|AES)", code, re.I):
        tags.append("crypto")
    if re.search(r"(sensors\.|accelerometer|media\.|playMusic|notification\.)", code):
        tags.append("media")
    if re.search(r"(select\(|selector|UiObject|accessibility\.|auto\(\)|auto\.waitFor)", code):
        tags.append("accessibility")
    if SHIZUKU_RE.search(code):
        tags.append("shizuku")
    if ROOT_RE.search(code):
        tags.append("root")
    if re.search(r"(files\.remove|\.remove\(|removeDir|\.rename\(|uninstall|forceStop|stopAll|"
                  r"am kill-all|lockScreen)", code):
        tags.append("destructive")
    if ACCESSIBILITY_RE.search(code):
        tags.append("touch")
    return tags


def static_risk_of(review: str):
    if review == "static_blocked":
        return "static_blocked"
    if review == "fail":
        return "fail"
    if review in ("partial", "static_partial"):
        return "partial"
    if review == "not_entry":
        return "not_entry"
    if review == "support":
        return "support"
    if review in ("pass", "static_ok"):
        return "pass"
    if review.startswith("compiled"):
        return "compiled"
    return review or "unknown"


def static_confidence(engine: str, review: str, conclusion: str) -> str:
    """How much to trust the ledger's negative verdict.

    Node `static_blocked` conclusions name a concrete missing API and are high
    confidence. Rhino `fail` rows often only say which routing target failed, so
    they are low confidence and must not be reported as a predicted script bug.
    """
    if review == "static_blocked" and engine == "node":
        return "high"
    if review == "fail":
        text = conclusion or ""
        if text.startswith("静态失败") or "Fails at line" in text or "静态失败：" in text:
            return "high"
        return "low"
    return "n/a"


def specific_known_missing(path: str, source: str, tags):
    missing = []
    if path == f"{NODE}/Node.js中使用UI/使用Android原生界面/main.js":
        missing.append("必须按项目运行（androidResources/res）")
    if path == f"{RHINO}/复杂界面/使用Android原生界面/main.js":
        missing.append("必须按项目运行（androidResources/res）")
    if path in (f"{RHINO}/协程/ui中使用协程/main.js", f"{RHINO}/协程/协程HelloWorld/main.js"):
        missing.append("必须按项目运行并启用 continuation")
    if path in {
        f"{RHINO}/项目与打包/自定义ABI/main.js",
        f"{RHINO}/项目与打包/自定义启动图/main.js",
        f"{RHINO}/项目与打包/自定义权限/main.js",
    }:
        missing.append("对应打包特性需打包后验证（本任务禁止构建）")
    if path == f"{QJS}/图色处理/SIFT特征匹配/main.js":
        missing.append("./output 目录（drawMatches/app.viewFile 前置）")
    if path == f"{QJS}/YOLO目标检测/模型管理.js":
        missing.append("模型库目录 /sdcard/脚本/模型库 可写")
    if path == f"{QJS}/项目与打包/dsh 手机客户端/main.js":
        missing.append("服务端可达性（https://106.55.18.198）尚未验证")
    if path == f"{QJS}/图像与视觉/QuickJS 象棋棋盘分析.js":
        missing.append("运行前需把棋局画面切到前台")
    if path == f"{QJS}/图色处理/11-窗口截图与ImGui绘制.js":
        missing.append("Android 14+ 无障碍；目标应用前台")
    if path == f"{RHINO}/图片与图色处理/找到QQ红点位置.js":
        missing.append("QQ 应用（未确认安装）")
    if path == f"{NODE}/无障碍/无障碍事件.js":
        missing.append("com.android.fileexplorer（包名未验证）")
    if path == f"{NODE}/Node.js中使用UI/Vue/Vue2+Vant(SFC)/main.node.js":
        missing.append("web/vue2-sfc-loader@0.8.4.js（未随样例分发）")
    if path == f"{RHINO}/HTTP网络请求/文件上传.js":
        missing.append("测试输入 /sdcard/1.txt（只读，不改用户文件）")
        missing.append("外部端点 http://posttestserver.com（可达性未验证）")
    if path in {
        f"{RHINO}/文件读写/读取文本文件.js",
        f"{RHINO}/文件读写/文件编码转换.js",
        f"{RHINO}/文件读写/文件编码转换(高级).js",
    }:
        missing.append("测试输入 /sdcard/1.txt（需预置；不覆盖用户文件）")
    if path == f"{QJS}/引擎与线程/QuickJS Pikafish 象棋引擎.js":
        missing.append("pikafish.nnue（候选 /sdcard/xiangqi-p4/pikafish.nnue 未确认）")
    if path in {f"{RHINO}/WebSocket/WebSocket.js", f"{QJS}/文件与网络/WebSocket.js"}:
        missing.append("公网 wss://echo.websocket.events 可达性")
    if path == f"{NODE}/图片与颜色处理/特征匹配(全分辨率找图).js":
        missing.append("外部网页 + 屏幕截图授权")
    if path == f"{RHINO}/图片与图色处理/找图/特征匹配(全分辨率找图).js":
        missing.append("外部网页 + 屏幕截图授权")
    if tags and "screenshot" in tags:
        missing.append("屏幕捕获/MediaProjection 授权（运行时手动允许）")
    if tags and "root" in tags:
        missing.append("Root/su 授权（设备未验证）")
    if tags and "shizuku" in tags:
        missing.append("Shizuku 服务与授权（设备未验证）")
    if tags and "accessibility" in tags:
        missing.append("无障碍服务连接（baseline 显示已配置，运行时确认）")
    return dedupe(missing)


def dedupe(values):
    out = []
    for value in values:
        if value and value not in out:
            out.append(value)
    return out


def validate_plan(plan):
    items = plan["items"]
    paths = [item["path"] for item in items]
    if len(items) != 289 or len(set(paths)) != 289:
        raise SystemExit("plan must contain 289 unique scripts")
    allowed_modes = {"run", "interactive", "observe", "requires_confirmation",
                     "blocked", "support"}
    required = {
        "path", "engine", "entry_kind", "project_root", "mode", "reason",
        "timeoutSeconds", "expected_evidence", "required_interactions",
        "known_missing", "covered_by", "source_review", "static_risk",
        "existingBaseline",
    }
    by_path = {item["path"]: item for item in items}
    for item in items:
        missing = required - set(item)
        if missing:
            raise SystemExit(f"{item['path']}: missing fields {sorted(missing)}")
        if item["mode"] not in allowed_modes:
            raise SystemExit(f"{item['path']}: bad mode {item['mode']}")
        if not item["reason"] or not item["expected_evidence"]:
            raise SystemExit(f"{item['path']}: empty reason/evidence")
        if item["mode"] in ("blocked", "support"):
            if item["timeoutSeconds"] != 0:
                raise SystemExit(f"{item['path']}: non-executed item has timeout")
        elif item["timeoutSeconds"] <= 0:
            raise SystemExit(f"{item['path']}: executable item has no timeout")
        if item["mode"] == "support":
            covered = item.get("covered_by")
            if not covered or covered not in by_path:
                raise SystemExit(f"{item['path']}: bad support covered_by")
        elif item.get("covered_by"):
            raise SystemExit(f"{item['path']}: unexpected covered_by")
        if item["mode"] == "blocked" and not item["known_missing"]:
            raise SystemExit(f"{item['path']}: blocked without missing prerequisite")
        baseline = item.get("existingBaseline")
        if baseline and baseline.get("sourceSha256") != item.get("sha256"):
            raise SystemExit(f"{item['path']}: baseline sha mismatch")
    if sum(plan["summary"]["byMode"].values()) != 289:
        raise SystemExit("mode summary does not add up to 289")


def build():
    ledger = read_json(LEDGER_PATH)
    sources = read_json(SOURCES_PATH)
    inventory = read_json(INVENTORY_PATH)

    scripts = [row for row in ledger if row.get("kind") == "script"]
    source_by_path = {npath(row["path"]): row for row in sources}
    all_paths = {npath(row["path"]) for row in ledger}
    script_paths = {npath(row["path"]) for row in scripts}
    baseline_by_path = load_existing_baseline()

    if len(scripts) != 289:
        raise SystemExit(f"expected 289 scripts, got {len(scripts)}")
    if len(source_by_path) != 289:
        raise SystemExit(f"expected 289 source rows, got {len(source_by_path)}")
    if set(source_by_path) != script_paths:
        raise SystemExit("ledger/sources path mismatch")

    project_index = []
    for project in PROJECTS:
        root = project["root"]
        declared_main = f"{root}/{project['main']}"
        source_entry = f"{root}/{project['source_entry']}"
        in_project = [p for p in script_paths if p == root or p.startswith(root + "/")]
        project_index.append({
            "root": root,
            "engine": project["engine"],
            "name": project["name"],
            "declared_main": project["main"],
            "main_exists": declared_main in script_paths,
            "source_entry": project["source_entry"],
            "source_entry_exists": source_entry in script_paths,
            "script_count": len(in_project),
            "support_count": sum(1 for p in in_project if p in SUPPORT),
            "note": project["note"],
        })

    items = []
    for row in scripts:
        path = npath(row["path"])
        source_row = source_by_path[path]
        source = source_row["source"]
        ledger_engine = row.get("engine", "unknown")
        engine = effective_engine(path, source)
        if engine == "unknown":
            engine = ledger_engine

        project = project_for(path)
        project_root = project["root"] if project else None
        project_main = project["main"] if project else None
        declared_main_path = f"{project_root}/{project_main}" if project else None
        source_entry_path = f"{project_root}/{project['source_entry']}" if project else None

        tags = tags_for(path, source)
        review = row.get("source_review", "unknown")
        static_conclusion = row.get("source_conclusion", "")
        risk = static_risk_of(review)
        confidence = static_confidence(engine, review, static_conclusion)
        static_note = ""
        override = STATIC_OVERRIDES.get(path)
        if override:
            risk = override["risk"]
            confidence = override["confidence"]
            static_note = override["note"]

        if path == declared_main_path:
            base_entry_kind = "project-entry"
        elif path == source_entry_path and project and project["main"] != project["source_entry"]:
            base_entry_kind = "project-source-entry"
        else:
            base_entry_kind = "script-entry"

        if path in SUPPORT:
            support = SUPPORT[path]
            entry_kind = support["entry_kind"]
            mode = "support"
            covered_by = support["covered_by"]
            reason = f"非独立入口：{support['role']}；由 {covered_by} 覆盖。"
            known_missing = []
            expected_evidence = [f"不作为入口运行；由 {covered_by} 的执行结果覆盖。"]
            required_interactions = []
        elif path in BLOCKED:
            blocked = BLOCKED[path]
            entry_kind = base_entry_kind
            mode = "blocked"
            covered_by = None
            reason = "阻塞：" + blocked["reason"]
            known_missing = list(blocked["missing"])
            expected_evidence = ["未执行；先补齐 known_missing 后重新评估。"]
            required_interactions = []
        else:
            covered_by = None
            entry_kind = base_entry_kind

            if path in CONFIRM:
                mode = "requires_confirmation"
            elif path in OBSERVE:
                mode = "observe"
            elif path in INTERACTIVE:
                mode = "interactive"
            elif engine == "rhino" and has_ui_directive(source):
                mode = "interactive"
            elif engine == "rhino" and path.startswith(f"{RHINO}/对话框/"):
                mode = "interactive"
            elif engine == "rhino" and path.startswith(f"{RHINO}/悬浮窗/"):
                mode = "interactive"
            else:
                mode = "run"

            tags = tags_for(path, source)

            if mode == "requires_confirmation":
                risk_note = CONFIRM[path]
                reason = "高风险动作：" + risk_note + " 必须显式批准后才能执行。"
                known_missing = specific_known_missing(path, source, tags)
                known_missing = dedupe(["用户显式批准（单一用例级）"] + known_missing)
                expected_evidence = [
                    "批准记录与执行前后状态快照（文件/task/settings/前台应用）。",
                    "操作后设备状态可核对；卸载/删除等不可恢复动作必须在批准时明确接受。",
                ]
                if "destructive" in tags or "touch" in tags:
                    expected_evidence.append("录屏或连续截图至少覆盖关键动作。")
                required_interactions = [
                    "执行前取得用户明确同意：" + risk_note,
                    "执行前快照并在执行后核对/恢复：" + snapshot_hint(path, source),
                ]
            elif mode == "observe":
                reason = "长驻/持续观察：启动后观察日志与画面，到时或手动停止。"
                known_missing = specific_known_missing(path, source, tags)
                expected_evidence = [
                    "运行至少 10 秒的连续日志/画面证据。",
                    "手动停止后无残留引擎、悬浮窗或监听。",
                ]
                required_interactions = observe_interactions(path, source, tags)
            elif mode == "interactive":
                reason = interactive_reason(path, source, tags)
                known_missing = specific_known_missing(path, source, tags)
                expected_evidence = ["界面/对话框截图。", "关键交互后的日志或状态变化。"]
                required_interactions = interactive_interactions(path, source, tags)
            else:
                reason = run_reason(path, source, tags)
                if path in RUN_NOTES:
                    reason += " " + RUN_NOTES[path]
                known_missing = specific_known_missing(path, source, tags)
                expected_evidence = ["脚本在 timeout 内结束，无未捕获异常。"]
                required_interactions = run_interactions(path, source, tags)

            # Static audit is evidence, not a runtime result.
            if risk in STATIC_FAIL:
                if confidence == "high":
                    reason += (f" 静态复核={review}（高置信度），预计会失败；"
                               "运行结果必须按实际失败记录。")
                else:
                    reason += (f" 静态复核={review}（低置信度：ledger 主要记录路由目标失败，"
                               "未逐行证明脚本必败），真机必须实测。")
                expected_evidence.append(
                    "记录异常栈/失败现象；不得把静态通过或修复计划当成运行通过。"
                )
                if static_conclusion and confidence == "high":
                    known_missing = dedupe(
                        known_missing + ["静态缺口：" + first_sentence(static_conclusion)]
                    )
            elif risk == "partial":
                reason += " 静态复核=partial，仍有未覆盖行为。"
            if static_note:
                reason += " " + static_note

            markers = extract_markers(code_view(source))
            if risk not in STATIC_FAIL:
                markers = [
                    marker for marker in markers
                    if not (
                        ("失败" in marker or "错误" in marker)
                        and not any(key in marker for key in ("完成", "通过", "OK", "成功", "==="))
                    )
                ]
            if markers:
                expected_evidence.append("关键日志包含：" + " / ".join(markers))
            if "screenshot" in tags:
                expected_evidence.append("获得一帧截图（宽高 > 0）或明确授权失败。")
            if "yolo" in tags:
                expected_evidence.append("输出 backend/模型名/帧数/耗时；模型加载失败要保留原因。")
            if "ocr" in tags:
                expected_evidence.append("输出识别块数量/文本/耗时；无权限或模型缺失要保留错误。")
            if "tasks" in tags:
                expected_evidence.append("任务查询结果与执行前后任务列表快照。")
            if "settings" in tags:
                expected_evidence.append("执行前后设置值快照。")
            expected_evidence = dedupe(expected_evidence)

        timeout = TIMEOUT_OVERRIDES.get(path, DEFAULT_TIMEOUT[mode])
        if mode in ("blocked", "support"):
            timeout = 0

        review = row.get("source_review", "unknown")
        item = {
            "path": path,
            "engine": engine,
            "ledger_engine": ledger_engine,
            "entry_kind": entry_kind,
            "project_root": project_root,
            "project_main": project_main,
            "mode": mode,
            "batch": MODE_ORDER[mode],
            "reason": reason,
            "timeoutSeconds": timeout,
            "expected_evidence": expected_evidence,
            "required_interactions": required_interactions,
            "known_missing": known_missing,
            "covered_by": covered_by,
            "prerequisites": prerequisites_for(path),
            "tags": tags_for(path, source),
            "risk": {
                "approvalRequired": mode == "requires_confirmation",
                "deviceDataChange": mode == "requires_confirmation",
                "thirdPartyApp": (
                    any(key in path for key in ("QQ", "Intent 与系统服务", "无障碍事件"))
                    or (mode == "requires_confirmation" and "touch" in tags)
                ),
            },
            "source_review": review,
            "static_risk": risk,
            "static_confidence": confidence,
            "static_note": static_note,
            "static_conclusion": row.get("source_conclusion", ""),
            "existingBaseline": baseline_by_path.get(path),
            "review_depth": "targeted" if path in TARGETED_REVIEW else "full",
            "review_note": (
                "大文件：已读头尾并做关键字/危险调用扫描，未逐行人工阅读。"
                if path in TARGETED_REVIEW else "已读取完整源码并分类。"
            ),
            "bytes": row.get("bytes"),
            "sha256": row.get("sha256"),
            "directives": row.get("directives", []),
        }
        items.append(item)

    # Keep the source order stable inside each batch for execution convenience.
    items.sort(key=lambda it: (it["batch"], it["engine"], it["path"]))
    for index, item in enumerate(items, 1):
        item["sequence"] = index

    by_mode = Counter(item["mode"] for item in items)
    by_engine = Counter(item["engine"] for item in items)
    by_ledger_engine = Counter(item["ledger_engine"] for item in items)
    by_entry = Counter(item["entry_kind"] for item in items)
    static_reviews = Counter(item["source_review"] for item in items)
    static_risks = Counter(item["static_risk"] for item in items)
    baseline_status = Counter(
        item["existingBaseline"]["status"]
        for item in items if item.get("existingBaseline")
    )
    targeted = [item["path"] for item in items if item["review_depth"] == "targeted"]

    top_fixes = [
        {
            "path": f"{RHINO}/控制台/UI中显示控制台.js",
            "problem": "XML 标签拼写为 <bottomnaviagtion>，inflate 会失败；"
                       "即使拼写修正，还需确认 XmlConverter 是否支持该控件。",
            "priority": "P0",
        },
        {
            "path": f"{RHINO}/应用/自动重命名所有apk文件.js",
            "problem": "递归扫描内置存储并批量重命名所有 APK，没有确认或空跑开关；"
                       "建议对齐 QuickJS 版的 RENAME=false 安全设计。",
            "priority": "P0",
        },
        {
            "path": f"{RHINO}/悬浮窗/悬浮窗运行脚本按钮简单版.js",
            "problem": "硬编码 /sdcard/脚本/test.js；同目录已有 test.js，但脚本不会用它。",
            "priority": "P0",
        },
        {
            "path": f"{QJS}/悬浮窗/悬浮窗运行脚本按钮简单版.js",
            "problem": "硬编码 /sdcard/脚本/test.js；样例树没有该文件。",
            "priority": "P0",
        },
        {
            "path": f"{QJS}/悬浮窗/悬浮运行脚本按钮.js",
            "problem": "硬编码 /sdcard/脚本/test.js；样例树没有该文件。",
            "priority": "P0",
        },
        {
            "path": f"{NODE}/Node.js中使用UI/React/main.node.js",
            "problem": "project.json main 指向缺失的 dist/main.node.js；开发分支执行 npm install/start，"
                       "本任务禁止构建。Vue3+CLI 同样。",
            "priority": "P0",
        },
        {
            "path": f"{NODE}/任务.js",
            "problem": "新增每日/每周/广播任务，路径硬编码到 /sdcard/脚本/*"
                       "（样例树没有这些文件）；只删除 task1，其余任务会残留。",
            "priority": "P1",
        },
        {
            "path": f"{QJS}/图色处理/SIFT特征匹配/main.js",
            "problem": "drawMatches 写 ./output/match_result.jpg，但未确保 ./output 存在；"
                       "随后 app.viewFile 可能打不开。",
            "priority": "P1",
        },
    ]

    plan = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "device": {
            "name": "Xiaomi 8",
            "serial": "ce4d2bdb",
            "owner": "primary/主控",
            "note": "本文件只是执行计划；生成过程未连接设备、未执行 ADB。",
        },
        "sources": {
            "inventory": "output/three-engine-audit-20261019/inventory.json",
            "sources": "output/three-engine-audit-20261019/sources.json",
            "sampleLedger": "output/three-engine-audit-20261019/sample-ledger.json",
            "sampleRoot": "apps/app/src/main/assets/sample",
            "inventorySummary": inventory.get("summary", {}),
        },
        "scope": {
            "filesInAudit": len(ledger),
            "scripts": len(scripts),
            "resources": sum(1 for row in ledger if row.get("kind") == "resource"),
            "projects": sum(1 for row in ledger if row.get("kind") == "project"),
            "allScriptsClassified": len(items),
            "runtimeResultsIncluded": False,
            "existingBaselineCount": len(baseline_by_path),
            "existingBaselineNote": "生成时已有 49 条 baseline 真机结果（本任务之前/并行产生），"
                                    "只用于对照与去重，不计入本轮运行覆盖。",
        },
        "policy": {
            "run": "可直接启动；需要权限时在 required_interactions 中列出；不修改用户数据。",
            "interactive": "需要人工操作对话框/UI/悬浮窗/目标应用；以截图和交互后日志为证据。",
            "observe": "长驻、循环或持续监听；启动后观察，手动停止或到时停止。",
            "support": "工程依赖/构建配置/子脚本/测试载荷，不作为独立入口；covered_by 指向覆盖入口。",
            "blocked": "硬前置缺失（构建产物、插件、模型、文件或外部 Intent）；补齐后重新评估。",
            "requires_confirmation": "实质删除/批量重命名/卸载安装/结束其它应用/真实触摸或改设置；"
                                     "每次执行前必须取得用户明确批准并做前后快照。",
            "staticReview": "ledger 的 source_review 只是静态复核，不是真机运行结果；"
                            "static_blocked/fail 仍要按 run 记录真实失败，不能算通过。",
        },
        "projectIndex": project_index,
        "topFixes": top_fixes,
        "summary": {
            "byMode": dict(sorted(by_mode.items())),
            "byEffectiveEngine": dict(sorted(by_engine.items())),
            "byLedgerEngine": dict(sorted(by_ledger_engine.items())),
            "byEntryKind": dict(sorted(by_entry.items())),
            "staticReviews": dict(sorted(static_reviews.items())),
            "byStaticRisk": dict(sorted(static_risks.items())),
            "executable": sum(
                by_mode[key] for key in ("run", "interactive", "observe", "requires_confirmation")
            ),
            "blocked": by_mode.get("blocked", 0),
            "support": by_mode.get("support", 0),
            "confirmationGated": by_mode.get("requires_confirmation", 0),
            "existingBaseline": {
                "count": len(baseline_by_path),
                "byStatus": dict(sorted(baseline_status.items())),
                "functionalPassTrue": sum(
                    1 for item in items
                    if item.get("existingBaseline")
                    and item["existingBaseline"].get("functionalPass") is True
                ),
                "note": "非本轮结果；49 条均有日志/证据路径，但 functionalPass 全为 false，"
                        "不能当通过率。",
            },
        },
        "coverage": {
            "allScriptsRead": True,
            "method": "289/289 源码已加载并按头尾、require、API、UI、危险调用、循环与资源路径分析；"
                      "大文件 8 个采用头尾+关键字扫描，未逐行人工阅读。",
            "targetedReview": targeted,
            "unreadFiles": [],
            "runtimeVerified": 0,
            "existingBaselineVerified": len(baseline_by_path),
            "deviceOperations": 0,
            "disclaimer": "本计划没有执行任何真机运行；所有 mode 都是待执行计划。"
                          "49 条 existingBaseline 是已有产物，不是本轮结果。",
        },
        "items": items,
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    validate_plan(plan)
    plan_path = OUT_DIR / "sample-plan.json"
    with plan_path.open("w", encoding="utf-8", newline="\n") as fh:
        json.dump(plan, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    notes = render_notes(plan)
    notes_path = OUT_DIR / "plan-notes.md"
    with notes_path.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write(notes)

    print(f"wrote {plan_path.relative_to(ROOT)} ({len(items)} items)")
    print(f"wrote {notes_path.relative_to(ROOT)}")
    print("modes:", dict(sorted(by_mode.items())))
    print("engines:", dict(sorted(by_engine.items())))


def snapshot_hint(path: str, source: str) -> str:
    if any(key in path for key in ("任务", "work_manager", "workManager")):
        return "queryTimedTasks/queryIntentTasks 列表"
    if "设置" in path or "settings" in source:
        return "settings 五个开关值"
    if any(key in path for key in ("卸载", "强制停止", "结束所有后台", "停止所有")):
        return "目标应用/脚本引擎列表"
    if any(key in path for key in ("重命名", "删除", "文件下载", "截图并保存", "保存日志",
                                    "写入文本", "读写文本", "编码转换", "播放音乐",
                                    "无障碍权限截图", "问号方块")):
        return "目标文件 mtime/sha256（必要时备份并在结束后恢复）"
    if any(key in path for key in ("手势", "触控", "坐标点击", "选择器", "模拟按键",
                                    "电量优化", "Intent")):
        return "当前前台应用与屏幕截图/录屏"
    if "QQ" in path:
        return "QQ 前台状态、屏幕截图与返回路径"
    return "相关文件、任务、设置或前台应用状态"


def prerequisites_for(path: str):
    if path == f"{RHINO}/数据库/数据库事务.js":
        return [f"{RHINO}/数据库/数据库增删改查.js"]
    if path == f"{NODE}/Node.js中使用UI/使用Android原生界面/main.js":
        return ["以项目方式运行（androidResources）"]
    if path in (f"{RHINO}/协程/ui中使用协程/main.js", f"{RHINO}/协程/协程HelloWorld/main.js"):
        return ["以项目方式运行并启用 continuation"]
    return []


def run_reason(path: str, source: str, tags) -> str:
    notes = []
    if "network" in tags:
        notes.append("包含外部网络请求，失败也按真实结果记录，不因网络字样跳过")
    if "screenshot" in tags:
        notes.append("先授权屏幕捕获")
    if "engine" in tags:
        notes.append("涉及 engines/threads")
    if "files" in tags:
        notes.append("有文件读写/临时文件")
    if not notes:
        notes.append("无人工交互，输出日志/toast 即可判定")
    return "可直接运行：" + "；".join(notes) + "。"


def interactive_reason(path: str, source: str, tags) -> str:
    if "dialog" in tags:
        return "需要人工交互：点按/输入对话框，检查返回值与后续日志。"
    if "floaty" in tags:
        return "需要人工交互：确认悬浮窗出现、操作控件并关闭。"
    if "ui" in tags:
        return "需要人工交互：检查界面渲染并操作关键控件。"
    return "需要人工交互：按样例说明操作后记录日志。"


def interactive_interactions(path: str, source: str, tags):
    out = []
    if "screenshot" in tags:
        out.append("首次运行允许屏幕捕获/MediaProjection。")
    if "accessibility" in tags and "touch" in tags:
        out.append("确认无障碍服务已连接。")
    if "root" in tags:
        out.append("确认 Root/su 授权。")
    if "shizuku" in tags:
        out.append("启动 Shizuku 并授权。")
    if "dialog" in tags:
        out.append("点按/输入对话框选项并提交。")
    if "floaty" in tags:
        out.append("授予悬浮窗权限；确认悬浮窗出现，操作后关闭。")
    if "ui" in tags:
        out.append("截图界面；按样例点击关键控件并记录状态。")
    if path == f"{QJS}/引擎与线程/QuickJS 系统事件监听测试.js":
        out.append("10 秒内发出一条系统通知。")
    if path in {f"{QJS}/图像与视觉/QuickJS 象棋棋盘分析.js",
               f"{QJS}/图色处理/11-窗口截图与ImGui绘制.js"}:
        out.append("运行后把目标应用/棋局切到前台并保持不动。")
    if path == f"{QJS}/项目与打包/dsh 手机客户端/main.js":
        out.append("在设置页输入可用的 dsh 服务地址（或确认离线错误提示）。")
    if path == f"{RHINO}/应用/打开应用.js":
        out.append("在 rawInput 中输入一个已安装应用名并确认。")
    if path == f"{RHINO}/应用/发送意图-文本消息分享.js":
        out.append("输入分享文本，选择 QQ 分享面板后返回。")
    if path == f"{RHINO}/图片与图色处理/找到QQ红点位置.js":
        out.append("允许启动 QQ 并保持其界面在前台，等待截图找色。")
    if path == f"{QJS}/Java 互操作/02-Intent 与系统服务.js":
        out.append("允许打开浏览器与本应用系统设置页，两次 back 后回到原界面。")
    if path == f"{QJS}/系统与设备/QuickJS 通知监听与全局动作.js":
        out.append("允许下拉通知栏并返回；可顺手发一条通知验证监听。")
    if path == f"{RHINO}/任务/读取文本文件.js":
        out.append("直接运行只会弹说明；若要验证读取，需先添加 text/plain 外部 Intent 任务。")
    if path == f"{NODE}/Node.js中使用UI/自定义组件/colored-button.js":
        out.append("点击两个 colored-button，观察颜色变化与 attrs toast。")
    if path == f"{NODE}/Node.js中使用UI/Vue/Vue2+Vant(SFC)/main.node.js":
        out.append("等待 sfc loader 下载/WebView 加载；失败时记录网络/对话框异常。")
    if path == f"{QJS}/YOLO目标检测/模型管理.js":
        out.append("只读检查模型列表/切换内置模型；不要点删除/重命名。")
    if not out:
        out.append("按样例提示完成一次交互并截图。")
    return dedupe(out)


def observe_interactions(path: str, source: str, tags):
    out = []
    if "screenshot" in tags:
        out.append("允许屏幕捕获/MediaProjection（或无障碍截图）。")
    if "accessibility" in tags:
        out.append("确认无障碍服务已连接。")
    if path == f"{RHINO}/事件与按键、触摸监听/Toast监听.js":
        out.append("让任一应用弹一条 Toast。")
    elif path == f"{RHINO}/事件与按键、触摸监听/按键监听.js":
        out.append("按音量上/下键或 Home/返回键触发日志。")
    elif path == f"{RHINO}/事件与按键、触摸监听/触摸监听.js":
        out.append("在屏幕上触摸/滑动几次。")
    elif path == f"{RHINO}/事件与按键、触摸监听/通知监听.js":
        out.append("发一条系统通知。")
    elif path == f"{RHINO}/事件与按键、触摸监听/长按返回退出当前程序.js":
        out.append("长按返回键触发连续返回（会退出当前应用）。")
    elif path == f"{RHINO}/事件与按键、触摸监听/音量键控制程序.js":
        out.append("按音量上键切换任务，最后按音量下键结束。")
    elif path == f"{RHINO}/多线程/多线程按键监听.js":
        out.append("按音量上键停止脚本。")
    elif path == f"{RHINO}/图片与图色处理/实时显示触摸点颜色.js":
        out.append("允许截图授权后触摸屏幕，观察颜色日志。")
    elif path == f"{RHINO}/图片与图色处理/找图/异步截图.js":
        out.append("保持屏幕画面变化，观察 screen_capture 日志。")
    elif path == f"{QJS}/系统与设备/指纹手势.js":
        out.append("在指纹传感器上滑动（up/down/left/right），观察方向日志。")
    elif path == f"{QJS}/YOLO目标检测/DNN/YOLO持续识别.js":
        out.append("在截图通道对话框中选择“高速截图（原方式）”。")
    elif path == f"{QJS}/YOLO目标检测/DNN/截图自动切换测试.js":
        out.append("运行中打开/关闭系统录屏，观察截图通道日志。")
    elif path == f"{NODE}/发送通知.js":
        out.append("观察 ongoing 通知；点击/删除通知后再确认脚本退出。")
    elif path == f"{NODE}/通知与Toast监听.js":
        out.append("发一条通知并触发一次 Toast。")
    elif path == f"{NODE}/无障碍/按键监听.js":
        out.append("按音量上键等触发 key_event 日志。")
    elif path == f"{NODE}/无障碍/无障碍事件.js":
        out.append("允许启动 com.android.fileexplorer 并保持前台。")
    elif path == f"{NODE}/使用Node.js模块/http-server-helloworld.js":
        out.append("允许打开浏览器到 http://127.0.0.1:3000 并看到 Hello, World!。")
    out.append("观察至少 10 秒日志/画面，随后手动停止。")
    out.append("停止后确认无残留引擎、悬浮窗或监听。")
    return dedupe(out)


def run_interactions(path: str, source: str, tags):
    out = []
    if "screenshot" in tags:
        out.append("首次运行允许屏幕捕获/MediaProjection。")
    if "accessibility" in tags:
        out.append("确认无障碍服务已连接。")
    if "root" in tags:
        out.append("确认 Root/su 授权。")
    if "shizuku" in tags:
        out.append("启动 Shizuku 并授权。")
    if path == f"{QJS}/回归测试/QuickJS 全模块回归测试.js":
        out.append("允许脚本按 Home，并在桌面执行两次真实滑动。")
    return dedupe(out)


def render_notes(plan) -> str:
    s = plan["summary"]
    lines = []
    lines.append("# 小米 8 三引擎示例执行计划（2026-10-19）")
    lines.append("")
    lines.append("> 目标设备：Xiaomi 8 / `ce4d2bdb`（由主控独占）。本文件是静态规划产物，"
                 "**没有执行任何 ADB、构建、发布或设备操作**。")
    lines.append("")
    lines.append("## 结论")
    lines.append("")
    lines.append(f"- 审计清单共 {plan['scope']['filesInAudit']} 个文件："
                 f"**{plan['scope']['scripts']} JS** + {plan['scope']['resources']} 资源 + "
                 f"{plan['scope']['projects']} project.json。")
    lines.append(f"- 289/289 JS 已纳入 `sample-plan.json`；模式分布："
                 f"run {s['byMode'].get('run', 0)}、interactive "
                 f"{s['byMode'].get('interactive', 0)}、observe "
                 f"{s['byMode'].get('observe', 0)}、requires_confirmation "
                 f"{s['byMode'].get('requires_confirmation', 0)}、blocked "
                 f"{s['byMode'].get('blocked', 0)}、support {s['byMode'].get('support', 0)}。")
    lines.append(f"- 有效引擎（按源码首行指令）：Rhino {s['byEffectiveEngine'].get('rhino', 0)}、"
                 f"QuickJS {s['byEffectiveEngine'].get('quickjs', 0)}、Node "
                 f"{s['byEffectiveEngine'].get('node', 0)}。")
    lines.append(f"- 审计表声明引擎：Rhino {s['byLedgerEngine'].get('rhino', 0)}、QuickJS "
                 f"{s['byLedgerEngine'].get('quickjs', 0)}、Node "
                 f"{s['byLedgerEngine'].get('node', 0)}。差异只有 "
                 "`Node.js启动Rhino引擎与通信/悬浮窗.js`：路径在 Node 项目下，"
                 "但首行是 `\"rhino\"`，实际由 Rhino 执行。")
    lines.append(f"- 需要确认的高风险用例 **{s['confirmationGated']}** 个；"
                 f"硬阻塞 **{s['blocked']}** 个；非入口 support **{s['support']}** 个。")
    lines.append("- 本任务**没有新增运行结果**；任何“静态通过”都不能写成“真机通过”。")
    baseline = s.get("existingBaseline", {})
    if baseline.get("count"):
        lines.append("")
        lines.append("### 已有真机 baseline（非本轮，勿重复声称）")
        lines.append("")
        lines.append(f"- `output/mi8-three-engines-20261019/baseline/` 生成时已有 "
                     f"**{baseline['count']}/289** 条真机记录；本任务只读取它们做对照，"
                     "不计入本轮覆盖。")
        status_text = "、".join(
            f"{key} {value}" for key, value in baseline.get("byStatus", {}).items()
        )
        lines.append(f"- 状态分布：{status_text}；"
                     f"`functionalPass=true` 为 {baseline.get('functionalPassTrue', 0)} 条。")
        lines.append(f"- 其余 {plan['scope']['scripts'] - baseline['count']} 个 JS 尚无真机记录，"
                     "仍是本轮执行计划的主体。")
        lines.append("- 已有记录里的 `runtime_error` 与静态复核互相印证（如 Node "
                     "`plugins` 模块缺失、Activity layoutId 报错），但仍需在修复后复跑。")
    lines.append("")
    lines.append("## 模式定义")
    lines.append("")
    lines.append("| 模式 | 含义 | 执行方式 |")
    lines.append("|---|---|---|")
    lines.append("| run | 可直接运行或仅需权限/无损测试输入 | 启动后看日志，60s 默认超时 |")
    lines.append("| interactive | 对话框/UI/悬浮窗/目标应用需要人工操作 | 截图 + 点击/输入 + 交互后日志 |")
    lines.append("| observe | 长驻、循环、监听或持续绘制 | 观察≥10s，手动停止并检查残留 |")
    lines.append("| support | 工程依赖/构建配置/子脚本/测试载荷 | 不作为入口；按 `covered_by` 覆盖 |")
    lines.append("| blocked | 构建产物、插件、模型、文件或 Intent 等硬前置缺失 | 不执行；补齐后重评 |")
    lines.append("| requires_confirmation | 删除/批量重命名/卸载/结束其它应用/真实触摸/改设置 | **逐项批准 + 前后快照/恢复** |")
    lines.append("")
    lines.append("## 执行顺序")
    lines.append("")
    lines.append("1. **batch 1（run）**：先在主屏、已授权截图/无障碍的前提下跑无交互用例；"
                 "失败也保留完整日志，不把静态 review 当结果。")
    lines.append("2. **batch 2（interactive）**：按项目入口优先（项目根目录 + project.json main），"
                 "逐个截图/点按；项目入口先于同目录零散脚本。")
    lines.append("3. **batch 3（observe）**：悬浮窗/监听/长循环；每个用例观察后手动停止，"
                 "确认没有残留引擎或悬浮窗。")
    lines.append("4. **batch 4（requires_confirmation）**：最后做，逐项取得用户批准；"
                 "先快照目标文件/任务/设置/前台应用，结束后核对或恢复。")
    lines.append("5. **blocked/support**：不占用设备时间；blocked 先补前置，support 由入口覆盖。")
    lines.append("")
    lines.append("## 项目入口（project_root）")
    lines.append("")
    lines.append("| 项目 | 引擎 | 声明 main | main 存在 | 源码入口 | 脚本数 |")
    lines.append("|---|---|---|---|---|---|")
    for project in plan["projectIndex"]:
        lines.append(
            f"| `{project['root']}` | {project['engine']} | `{project['declared_main']}` | "
            f"{'是' if project['main_exists'] else '**否**'} | `{project['source_entry']}` | "
            f"{project['script_count']} |"
        )
    lines.append("")
    lines.append("React 与 Vue3+CLI 的 `project.json` main 指向 `dist/main.node.js`，"
                 "样例树没有 dist/node_modules；其开发分支会执行 npm install/start，"
                 "本任务禁止构建，因此标 blocked，不拿 `main.node.js` 冒充可运行项目入口。")
    lines.append("")
    lines.append("## 建议最优先修的样例错误（未修改）")
    lines.append("")
    for index, fix in enumerate(plan["topFixes"], 1):
        lines.append(f"{index}. **{fix['priority']}** `{fix['path']}`")
        lines.append(f"   - {fix['problem']}")
    lines.append("")
    lines.append("此外，Node 侧有 28 个 `static_blocked`，集中在 "
                 "`engines/lang/ui/dialogs/accessibility/zip/media/plugins/datastore` 等"
                 "Node24 模块缺口；它们不是“样例写错一行”，需要按模块补 API 后再跑。")
    risks = s.get("byStaticRisk", {})
    lines.append("静态风险分布（不是运行结果）："
                 f"pass/static_ok {risks.get('pass', 0)}、compiled {risks.get('compiled', 0)}、"
                 f"partial {risks.get('partial', 0)}、static_blocked {risks.get('static_blocked', 0)}、"
                 f"fail {risks.get('fail', 0)}、not_entry {risks.get('not_entry', 0)}、"
                 f"support {risks.get('support', 0)}。")
    lines.append("")
    lines.append("> 审计结论并非全部可信：`Rhino 引擎/HTTP网络请求/获取网页.js` 的 ledger 结论称 URL 缺 scheme，"
                 "但当前源码是 `https://www.baidu.com`。计划保留审计原文，同时把该项按 partial 处理并在 "
                 "`static_note` 标记矛盾；其余 Rhino `fail` 若只写路由目标失败，标为低置信度，真机必须实测。")
    lines.append("")
    lines.append("## 测试数据与安全边界")
    lines.append("")
    lines.append("- 只允许无损测试输入：`/sdcard/1.txt` 等固定路径先检查是否存在，"
                 "存在则备份并在用例后恢复；不存在才创建测试内容。")
    lines.append("- 专用目录/文件（zip_test、quickjs-watch-demo、quickjs-sqlite-demo.db、"
                 "engine-matrix）可按样例清理自己的测试产物，但不得递归删除用户目录。")
    lines.append("- `Rhino 引擎/文件读写/删除所有空文件夹.js`、Rhino 自动重命名 APK"
                 "（以及 QuickJS 版把 `RENAME` 改为 true 时）、`结束所有后台进程`、"
                 "`停止所有正在运行的脚本`、卸载/强制停止、所有真实手势/触控、"
                 "设置/任务修改：**逐项批准**，不得批量默认同意。")
    lines.append("- 不要把 `QuickJS 自动重命名所有 APK 文件.js` 的 `RENAME` 改成 true；"
                 "当前默认 false 是只读空跑。")
    lines.append("- 不执行 npm install/npm run build、不安装插件、不 ADB、不编辑产品。")
    lines.append("")
    lines.append("## 覆盖与未读范围")
    lines.append("")
    lines.append("- **未读文件：0**。289 个 JS 全部加载；`unreadFiles` 为空。")
    lines.append("- **逐行/完整读取**：除下表 8 个大文件外的 281 个 JS。")
    lines.append("- **头尾 + 关键字扫描（未逐行人工阅读）**：")
    for path in plan["coverage"]["targetedReview"]:
        lines.append(f"  - `{path}`")
    lines.append("- 静态复核来源是已有 `sample-ledger.json`；它只说明源码/API 层面的问题，"
                 "**不等于真机行为**。本轮没有新增任何运行结论。")
    lines.append("- 未做：ADB、真机启动、截图授权、插件安装、构建、发布、产品文件修改。")
    lines.append("")
    lines.append("## 产物")
    lines.append("")
    lines.append("- `output/mi8-three-engines-20261019/sample-plan.json`：289 条完整机器可读计划，"
                 "含 path/engine/entry_kind/project_root/mode/reason/timeoutSeconds/"
                 "expected_evidence/required_interactions/known_missing，以及 static review 原文。")
    lines.append("- `tools/mi8-sample-plan.py`：本计划的生成器（只读审计 + 写上述两个输出）。")
    lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    build()
