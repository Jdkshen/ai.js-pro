"use strict";
var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __commonJS = (cb, mod) => function __require() {
  return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
};
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// package.json
var require_package = __commonJS({
  "package.json"(exports2, module2) {
    module2.exports = {
      name: "aijspro-vscode",
      displayName: "AI.js Pro",
      description: "\u8FDE\u63A5 AI.js Pro\uFF08Android\uFF09\uFF1A\u8FD0\u884C\u5F53\u524D\u6587\u4EF6/\u6574\u4E2A\u5DE5\u7A0B\u3001\u5B9E\u65F6\u65E5\u5FD7\u3001Rhino \u65AD\u70B9\u8C03\u8BD5\u3001\u4FDD\u5B58\u81EA\u52A8\u63A8\u9001\u3001\u8BBE\u5907\u6587\u4EF6\u6D4F\u89C8\u4E0E\u62C9\u53D6\u3002",
      version: "0.3.3",
      publisher: "jdkshen",
      license: "MIT",
      engines: {
        vscode: "^1.85.0"
      },
      categories: [
        "Programming Languages",
        "Other"
      ],
      icon: "images/icon.png",
      main: "./dist/extension.js",
      activationEvents: [
        "onStartupFinished"
      ],
      contributes: {
        commands: [
          {
            command: "aijspro.help",
            title: "AI.js Pro: \u4F7F\u7528\u6559\u7A0B(Help)",
            icon: "$(book)"
          },
          {
            command: "aijspro.connect",
            title: "AI.js Pro: \u8FDE\u63A5\u5230\u65B0\u8BBE\u5907(Connect)",
            icon: "$(plug)"
          },
          {
            command: "aijspro.selectDevice",
            title: "AI.js Pro: \u9009\u62E9\u8BBE\u5907(Select Device)",
            icon: "$(device-mobile)"
          },
          {
            command: "aijspro.scanDevices",
            title: "AI.js Pro: \u91CD\u65B0\u626B\u63CF\u8BBE\u5907(Scan Devices)",
            icon: "$(sync)"
          },
          {
            command: "aijspro.forgetDevices",
            title: "AI.js Pro: \u5220\u9664\u5DF2\u8BB0\u4F4F\u7684\u8BBE\u5907(Delete Devices)",
            icon: "$(trash)"
          },
          {
            command: "aijspro.run",
            title: "AI.js Pro: \u8FD0\u884C(Run)",
            icon: "$(run)"
          },
          {
            command: "aijspro.runFile",
            title: "AI.js Pro: \u8FD0\u884C\u5355\u6587\u4EF6(Run File)",
            icon: "$(play)"
          },
          {
            command: "aijspro.runProject",
            title: "AI.js Pro: \u8FD0\u884C\u9879\u76EE(Run Project)",
            icon: "$(cloud-upload)"
          },
          {
            command: "aijspro.rerun",
            title: "AI.js Pro: \u91CD\u65B0\u8FD0\u884C(Rerun)",
            icon: "$(debug-restart)"
          },
          {
            command: "aijspro.stop",
            title: "AI.js Pro: \u505C\u6B62\u5F53\u524D\u811A\u672C(Stop)",
            icon: "$(debug-stop)"
          },
          {
            command: "aijspro.stopAll",
            title: "AI.js Pro: \u505C\u6B62\u6240\u6709\u811A\u672C(Stop All)",
            icon: "$(stop-circle)"
          },
          {
            command: "aijspro.inspectLayout",
            title: "AI.js Pro: \u5E03\u5C40\u5206\u6790(Inspect Layout)",
            icon: "$(list-tree)"
          },
          {
            command: "aijspro.pickColor",
            title: "AI.js Pro: \u56FE\u8272\u52A9\u624B(Screenshot & Pick Color)",
            icon: "$(symbol-color)"
          },
          {
            command: "aijspro.browseFiles",
            title: "AI.js Pro: \u6D4F\u89C8\u8BBE\u5907\u6587\u4EF6(Browse Device Files)",
            icon: "$(folder-opened)"
          },
          {
            command: "aijspro.saveFile",
            title: "AI.js Pro: \u4FDD\u5B58\u5F53\u524D\u6587\u4EF6(Save)",
            icon: "$(save)"
          },
          {
            command: "aijspro.saveProject",
            title: "AI.js Pro: \u4FDD\u5B58\u9879\u76EE(Save Project)",
            icon: "$(archive)"
          },
          {
            command: "aijspro.newRhinoProject",
            title: "AI.js Pro: \u65B0\u5EFA\u7B2C\u4E00\u4EE3API\u9879\u76EE(Rhino)",
            icon: "$(new-folder)"
          },
          {
            command: "aijspro.newQuickJsProject",
            title: "AI.js Pro: \u65B0\u5EFA\u7B2C\u4E8C\u4EE3API\u9879\u76EE(QuickJS)",
            icon: "$(new-folder)"
          },
          {
            command: "aijspro.newRhinoProjectInWorkspace",
            title: "AI.js Pro: \u5728\u5DE5\u4F5C\u533A\u5EFA\u7ACB\u7B2C\u4E00\u4EE3API\u9879\u76EE(Rhino)"
          },
          {
            command: "aijspro.newQuickJsProjectInWorkspace",
            title: "AI.js Pro: \u5728\u5DE5\u4F5C\u533A\u5EFA\u7ACB\u7B2C\u4E8C\u4EE3API\u9879\u76EE(QuickJS)"
          },
          {
            command: "aijspro.debug",
            title: "AI.js Pro: \u8C03\u8BD5\u5F53\u524D\u6587\u4EF6(Debug File)",
            icon: "$(debug-alt)"
          },
          {
            command: "aijspro.addDebugConfig",
            title: "AI.js Pro: \u589E\u52A0\u8C03\u8BD5\u8BBE\u5907\u914D\u7F6E(Add Debug Config)",
            icon: "$(gear)"
          },
          {
            command: "aijspro.disconnect",
            title: "AI.js Pro: \u65AD\u5F00\u6240\u6709\u8FDE\u63A5(Disconnect)",
            icon: "$(debug-disconnect)"
          },
          {
            command: "aijspro.refreshFiles",
            title: "AI.js Pro: \u5237\u65B0\u6587\u4EF6(Refresh Directory)",
            icon: "$(refresh)"
          },
          {
            command: "aijspro.showLogs",
            title: "AI.js Pro: \u663E\u793A\u65E5\u5FD7(Show Logs)",
            icon: "$(output)"
          },
          {
            command: "aijspro.togglePushOnSave",
            title: "AI.js Pro: \u5F00\u5173\u300C\u4FDD\u5B58\u65F6\u81EA\u52A8\u63A8\u9001\u300D(Toggle Push On Save)"
          },
          {
            command: "aijspro.menu",
            title: "AI.js Pro: \u529F\u80FD\u83DC\u5355",
            icon: {
              light: "images/icon.png",
              dark: "images/icon.png"
            }
          },
          {
            command: "aijspro.pushFile",
            title: "AI.js Pro: \u63A8\u9001\u5F53\u524D\u6587\u4EF6\u5230\u624B\u673A",
            icon: "$(save-all)"
          },
          {
            command: "aijspro.openDeviceFile",
            title: "\u6253\u5F00"
          },
          {
            command: "aijspro.downloadFile",
            title: "\u4E0B\u8F7D\u5230\u5DE5\u4F5C\u533A"
          },
          {
            command: "aijspro.runDeviceFile",
            title: "\u5728\u624B\u673A\u4E0A\u8FD0\u884C"
          }
        ],
        menus: {
          "editor/title": [
            {
              command: "aijspro.menu",
              when: "resourceExtname == .js || editorLangId == javascript",
              group: "navigation@9"
            }
          ],
          "view/item/context": [
            {
              command: "aijspro.openDeviceFile",
              when: "view == aijsproFiles && (viewItem == aijspro.file || viewItem == aijspro.script)",
              group: "inline@1"
            },
            {
              command: "aijspro.runDeviceFile",
              when: "view == aijsproFiles && viewItem == aijspro.script",
              group: "aijspro@1"
            },
            {
              command: "aijspro.downloadFile",
              when: "view == aijsproFiles && (viewItem == aijspro.file || viewItem == aijspro.script)",
              group: "aijspro@2"
            },
            {
              command: "aijspro.refreshFiles",
              when: "view == aijsproFiles",
              group: "navigation@1"
            }
          ],
          commandPalette: [
            {
              command: "aijspro.openDeviceFile",
              when: "false"
            },
            {
              command: "aijspro.downloadFile",
              when: "false"
            },
            {
              command: "aijspro.runDeviceFile",
              when: "false"
            }
          ]
        },
        views: {
          explorer: [
            {
              id: "aijsproFiles",
              name: "AI.js Pro \u8BBE\u5907\u6587\u4EF6"
            }
          ]
        },
        configuration: {
          title: "AI.js Pro",
          properties: {
            "aijspro.devices": {
              type: "array",
              items: {
                type: "string"
              },
              default: [],
              description: "\u5DF2\u8BB0\u4F4F\u7684\u8BBE\u5907\u5730\u5740\uFF08IP\u3001IP:\u7AEF\u53E3\u6216 [IPv6]:\u7AEF\u53E3\uFF09\u3002\u624B\u673A\u4FA7\u6253\u5F00\u300CAI.js Pro \u7535\u8111\u8FDE\u63A5\u300D\u540E\u4F1A\u81EA\u52A8\u56DE\u8FDE\u672C\u673A 8327 \u7AEF\u53E3\uFF0C\u8FD9\u91CC\u4E3B\u8981\u7528\u4E8E\u7535\u8111\u4E3B\u52A8\u8FDE\u63A5\u624B\u673A 9317 \u7684\u573A\u666F\u3002"
            },
            "aijspro.listenPort": {
              type: "number",
              default: 8327,
              description: "\u63A5\u6536\u624B\u673A\u4E3B\u52A8\u56DE\u8FDE\u7684\u76D1\u542C\u7AEF\u53E3\uFF0C\u9ED8\u8BA4 8327\uFF1B\u624B\u673A\u7AEF\u56DE\u8FDE\u7AEF\u53E3\u4E5F\u9700\u4E0E\u6B64\u4E00\u81F4\u3002"
            },
            "aijspro.autoConnect": {
              type: "boolean",
              default: true,
              description: "\u542F\u52A8\u65F6\u81EA\u52A8\u8FDE\u63A5\u5DF2\u8BB0\u4F4F\u7684\u8BBE\u5907\uFF0C\u5E76\u5728\u6389\u7EBF\u540E\u91CD\u8BD5\u3002"
            },
            "aijspro.ignoredDevices": {
              type: "array",
              items: {
                type: "string"
              },
              default: [],
              description: "\u5DF2\u5220\u9664\u8BBE\u5907\u7684\u5730\u5740\uFF1A\u505C\u6B62\u81EA\u52A8\u8FDE\u63A5\u5E76\u5FFD\u7565\u624B\u673A\u4E3B\u52A8\u56DE\u8FDE\u3002\u5220\u9664\u547D\u4EE4\u7EF4\u62A4\u6B64\u5217\u8868\uFF0C\u624B\u52A8\u8FDE\u63A5\u5BF9\u5E94\u5730\u5740\u65F6\u81EA\u52A8\u89E3\u9664\u3002"
            },
            "aijspro.showLogsOnConnect": {
              type: "boolean",
              default: true,
              description: "\u9996\u6B21\u8FDE\u63A5\u5230\u624B\u673A\u65F6\u81EA\u52A8\u5207\u6362\u5230 AI.js Pro \u8F93\u51FA\u65E5\u5FD7\uFF0C\u5E76\u5728\u72B6\u6001\u680F\u663E\u793A\u8BBE\u5907 IP\u3002"
            },
            "aijspro.pushOnSave": {
              type: "boolean",
              default: false,
              description: "\u4FDD\u5B58\u6587\u4EF6\u65F6\u81EA\u52A8\u628A\u8BE5\u6587\u4EF6\u63A8\u9001\u5230\u624B\u673A\u7684\u811A\u672C\u76EE\u5F55\uFF08\u5DE5\u7A0B\u76EE\u5F55\u4E0B\u6309\u300C\u5DE5\u7A0B\u540D/\u76F8\u5BF9\u8DEF\u5F84\u300D\u5B58\u653E\uFF09\u3002"
            },
            "aijspro.exclude": {
              type: "array",
              items: {
                type: "string"
              },
              default: [
                "node_modules",
                ".git",
                ".vscode",
                ".artifacts",
                "dist",
                "build"
              ],
              description: "\u63A8\u9001\u6574\u4E2A\u5DE5\u7A0B\u65F6\u5FFD\u7565\u7684\u76EE\u5F55/\u6587\u4EF6\u540D\u3002"
            },
            "aijspro.adbPath": {
              type: "string",
              default: "",
              description: "USB \u8BBE\u5907\u626B\u63CF\u4F7F\u7528\u7684 adb \u53EF\u6267\u884C\u6587\u4EF6\u8DEF\u5F84\u3002\u7559\u7A7A\u65F6\u67E5\u627E Android SDK \u548C\u7CFB\u7EDF PATH\u3002"
            }
          }
        },
        debuggers: [
          {
            type: "aijspro",
            label: "AI.js Pro\uFF08Rhino \u65AD\u70B9\u8C03\u8BD5\uFF09",
            languages: [
              "javascript"
            ],
            configurationAttributes: {
              launch: {
                required: [],
                properties: {
                  program: {
                    type: "string",
                    description: "\u8981\u8C03\u8BD5\u7684\u811A\u672C\u6587\u4EF6\uFF08\u9ED8\u8BA4\u5F53\u524D\u6253\u5F00\u7684\u6587\u4EF6\uFF09\u3002"
                  },
                  host: {
                    type: "string",
                    description: "\u624B\u673A\u5730\u5740\uFF08\u9ED8\u8BA4\u4F7F\u7528\u5DF2\u8FDE\u63A5/\u5DF2\u8BB0\u4F4F\u7684\u8BBE\u5907\uFF09\u3002"
                  },
                  trace: {
                    type: "boolean",
                    description: "\u6253\u5370\u8C03\u8BD5\u534F\u8BAE\u7EC6\u8282\u5230\u300CAI.js Pro\u300D\u8F93\u51FA\u9762\u677F\u3002",
                    default: false
                  }
                }
              }
            },
            initialConfigurations: [
              {
                type: "aijspro",
                request: "launch",
                name: "AI.js Pro: \u8C03\u8BD5\u5F53\u524D\u6587\u4EF6\uFF08Rhino\uFF09",
                program: "${file}"
              }
            ],
            configurationSnippets: [
              {
                label: "AI.js Pro: \u8C03\u8BD5\u5F53\u524D\u6587\u4EF6\uFF08Rhino\uFF09",
                description: "\u628A\u5F53\u524D\u6587\u4EF6\u53D1\u9001\u5230\u624B\u673A\uFF0C\u7528 Rhino \u8C03\u8BD5\u5668\u65AD\u70B9\u8C03\u8BD5",
                body: {
                  type: "aijspro",
                  request: "launch",
                  name: "AI.js Pro: \u8C03\u8BD5\u5F53\u524D\u6587\u4EF6\uFF08Rhino\uFF09",
                  program: '^"\\${file}^"'
                }
              }
            ]
          }
        ]
      },
      scripts: {
        test: "node --test test/manager.test.cjs test/connection.test.cjs test/devicePicker.test.cjs test/adb.test.cjs",
        "test:devices": "node --test test/manager.test.cjs",
        "test:connection": "node --test test/connection.test.cjs",
        build: "esbuild src/extension.ts --bundle --platform=node --format=cjs --target=node18 --external:vscode --outfile=dist/extension.js",
        watch: "npm run build -- --watch",
        typecheck: "tsc --noEmit",
        "vscode:prepublish": "npm run build",
        package: "vsce package --no-dependencies"
      },
      devDependencies: {
        "@types/node": "^22.10.0",
        "@types/vscode": "^1.85.0",
        "@vscode/vsce": "^3.2.1",
        esbuild: "^0.24.0",
        typescript: "^5.6.3"
      }
    };
  }
});

// src/extension.ts
var extension_exports = {};
__export(extension_exports, {
  activate: () => activate,
  deactivate: () => deactivate
});
module.exports = __toCommonJS(extension_exports);
var fs4 = __toESM(require("fs"));
var import_crypto = require("crypto");
var path5 = __toESM(require("path"));
var import_util = require("util");
var vscode7 = __toESM(require("vscode"));

// src/color.ts
var fs = __toESM(require("fs"));
var path = __toESM(require("path"));
var vscode2 = __toESM(require("vscode"));

// src/editor-tracker.ts
var vscode = __toESM(require("vscode"));
var lastEditor;
function isScriptEditor(editor) {
  const scheme = editor?.document.uri.scheme;
  return scheme === "file" || scheme === "untitled";
}
function trackScriptEditors(context) {
  const active = vscode.window.activeTextEditor;
  if (active && isScriptEditor(active)) {
    lastEditor = active;
  }
  context.subscriptions.push(
    vscode.window.onDidChangeActiveTextEditor((editor) => {
      if (editor && isScriptEditor(editor) && !editor.document.isClosed) {
        lastEditor = editor;
      }
    })
  );
}
function pickScriptEditor() {
  const active = vscode.window.activeTextEditor;
  if (active && isScriptEditor(active) && !active.document.isClosed) {
    return active;
  }
  if (lastEditor && !lastEditor.document.isClosed) {
    return lastEditor;
  }
  return void 0;
}

// src/color.ts
var ColorAssistant = class {
  constructor(context, manager2, log) {
    this.context = context;
    this.manager = manager2;
    this.log = log;
  }
  panel;
  conn;
  shotUri;
  shotWidth = 0;
  shotHeight = 0;
  busy = false;
  logListener;
  pendingTest;
  log;
  /** 把片段插到目标脚本文件的光标处；实在没有脚本编辑器才新建临时文件。 */
  async insert(code, what) {
    const target = pickScriptEditor();
    if (target) {
      await target.edit((builder) => builder.insert(target.selection.active, code));
      vscode2.window.setStatusBarMessage(
        `AI.js Pro: ${what}\u5DF2\u63D2\u5165 ${path.basename(target.document.fileName)}`,
        3e3
      );
      return;
    }
    const document = await vscode2.workspace.openTextDocument({ content: code, language: "javascript" });
    await vscode2.window.showTextDocument(document, { preview: true });
    vscode2.window.setStatusBarMessage(`AI.js Pro: ${what}\u5DF2\u63D2\u5165\u4E34\u65F6\u6587\u4EF6\uFF08\u5F53\u524D\u6CA1\u6709\u6253\u5F00\u7684\u6587\u4EF6\uFF09`, 3e3);
  }
  async open() {
    const conn = await this.manager.pickConnection();
    if (!conn?.attached) {
      vscode2.window.showWarningMessage("AI.js Pro: \u6CA1\u6709\u53EF\u7528\u7684\u8BBE\u5907\u8FDE\u63A5\uFF08\u5148\u8FDE\u63A5\u624B\u673A\uFF09");
      return;
    }
    this.conn = conn;
    this.watchDeviceLog(conn);
    const created = !this.panel;
    if (!this.panel) {
      this.panel = vscode2.window.createWebviewPanel(
        "aijsproColor",
        "AI.js Pro \u56FE\u8272\u52A9\u624B",
        vscode2.ViewColumn.Beside,
        {
          enableScripts: true,
          retainContextWhenHidden: true,
          localResourceRoots: [this.storageRoot()]
        }
      );
      this.panel.webview.html = buildHtml();
      this.panel.onDidDispose(() => {
        this.panel = void 0;
      });
      this.panel.webview.onDidReceiveMessage((message) => void this.onMessage(message));
    }
    this.panel.reveal(vscode2.ViewColumn.Beside, true);
    if (!created) {
      await this.refresh();
    }
  }
  storageRoot() {
    return this.context.storageUri ?? this.context.globalStorageUri;
  }
  /** 监听设备日志，从中抽取测试脚本回传的 `AIJS_TEST {...}` 结果行。 */
  watchDeviceLog(conn) {
    this.logListener?.dispose();
    const onLog = (line) => {
      const match = /AIJS_TEST (\{.*\})\s*$/.exec(line.trim());
      if (!match) {
        return;
      }
      const pending = this.takePendingTest();
      if (!pending) {
        return;
      }
      let parsed = { error: "\u7ED3\u679C\u89E3\u6790\u5931\u8D25" };
      try {
        parsed = JSON.parse(match[1]);
      } catch {
      }
      clearTimeout(pending.timer);
      pending.resolve(parsed);
    };
    conn.on("log", onLog);
    this.logListener = { dispose: () => conn.removeListener("log", onLog) };
  }
  /** 取走等待中的测试回调（同步清空，避免重复解析多条日志）。 */
  takePendingTest() {
    const pending = this.pendingTest;
    this.pendingTest = void 0;
    return pending;
  }
  /** 「测试」：把一段临时脚本发到手机上跑（无障碍重新截图 → 找色/找图），回传命中坐标。 */
  async runDeviceTest(message) {
    const conn = this.conn;
    if (!conn) {
      vscode2.window.showWarningMessage("AI.js Pro: \u8BBE\u5907\u672A\u8FDE\u63A5");
      return;
    }
    if (this.pendingTest) {
      vscode2.window.showWarningMessage("AI.js Pro: \u4E0A\u4E00\u4E2A\u6D4B\u8BD5\u8FD8\u6CA1\u7ED3\u675F");
      return;
    }
    const script = buildTestScript(message);
    if (!script) {
      this.post({ type: "testResult", result: { kind: message?.kind, error: "\u53C2\u6570\u4E0D\u8DB3" } });
      return;
    }
    this.post({ type: "testBusy", on: true });
    try {
      const resultPromise = new Promise((resolve) => {
        const timer = setTimeout(
          () => resolve({ kind: message?.kind, error: "\u7B49\u5F85\u624B\u673A\u7ED3\u679C\u8D85\u65F6\uFF08\u811A\u672C\u6CA1\u8DD1\u8D77\u6765\uFF1F\uFF09" }),
          12e3
        );
        this.pendingTest = { resolve, timer };
      });
      await conn.run("aijs-color-test.js", "aijs-color-test.js", script);
      const result = await resultPromise;
      this.log(`[\u56FE\u8272] test ${JSON.stringify(result)}`);
      this.post({ type: "testResult", result });
    } catch (error) {
      this.post({
        type: "testResult",
        result: { kind: message?.kind, error: error.message }
      });
    } finally {
      const stale = this.takePendingTest();
      if (stale) {
        clearTimeout(stale.timer);
      }
      this.post({ type: "testBusy", on: false });
    }
  }
  async refresh() {
    if (!this.conn || this.busy) {
      return;
    }
    this.busy = true;
    this.post({ type: "busy", on: true });
    try {
      const shot = await this.conn.captureScreenshot();
      const dir = vscode2.Uri.joinPath(this.storageRoot(), "screenshots");
      await fs.promises.mkdir(dir.fsPath, { recursive: true });
      const file = vscode2.Uri.joinPath(dir, `shot-${Date.now()}.png`);
      await fs.promises.writeFile(file.fsPath, stripColorProfile(shot.png));
      this.shotUri = file;
      this.shotWidth = shot.width;
      this.shotHeight = shot.height;
      const uri = this.panel?.webview.asWebviewUri(file).toString() ?? "";
      this.post({
        type: "shot",
        uri,
        width: shot.width,
        height: shot.height,
        device: this.conn.deviceName
      });
      this.log(
        `[\u56FE\u8272] ${this.conn.deviceName} screenshot ${shot.width}x${shot.height}, ${(shot.png.length / 1024).toFixed(0)} KB`
      );
    } catch (error) {
      const message = error.message;
      this.post({ type: "error", message });
      this.log(`[\u56FE\u8272] \u622A\u56FE\u5931\u8D25\uFF1A${message}`);
      vscode2.window.showErrorMessage(`AI.js Pro \u622A\u56FE\u5931\u8D25\uFF1A${message}`);
    } finally {
      this.busy = false;
      this.post({ type: "busy", on: false });
    }
  }
  post(message) {
    void this.panel?.webview.postMessage(message);
  }
  async onMessage(message) {
    switch (message?.type) {
      case "ready":
        await this.refresh();
        return;
      case "refresh":
        await this.refresh();
        return;
      case "copy":
        await vscode2.env.clipboard.writeText(String(message.text ?? ""));
        vscode2.window.setStatusBarMessage("AI.js Pro: \u5DF2\u590D\u5236\u5230\u526A\u8D34\u677F", 3e3);
        return;
      case "insertCode":
        await this.insert(String(message.code ?? ""), "\u4EE3\u7801");
        return;
      case "insertPick":
        await this.insert(
          pickCode(
            Number(message.x ?? 0),
            Number(message.y ?? 0),
            String(message.argb ?? "")
          ),
          "\u53D6\u8272\u4EE3\u7801"
        );
        return;
      case "insertFindColor":
        await this.insert(
          findColorCode(
            Number(message.x ?? 0),
            Number(message.y ?? 0),
            String(message.argb ?? ""),
            this.shotWidth,
            this.shotHeight
          ),
          "\u627E\u8272\u4EE3\u7801"
        );
        return;
      case "insertFindMulti":
        await this.insert(
          findMultiCode(
            {
              x: Number(message.first?.x ?? 0),
              y: Number(message.first?.y ?? 0),
              argb: String(message.first?.argb ?? "")
            },
            Array.isArray(message.others) ? message.others.map((item) => ({
              dx: Number(item?.dx ?? 0),
              dy: Number(item?.dy ?? 0),
              argb: String(item?.argb ?? "")
            })) : [],
            this.shotWidth,
            this.shotHeight
          ),
          "\u591A\u70B9\u627E\u8272\u4EE3\u7801"
        );
        return;
      case "saveTemplate":
        await this.saveTemplate(String(message.dataUrl ?? ""), String(message.name ?? ""));
        return;
      case "saveShot":
        await this.saveShotCopy();
        return;
      case "test":
        await this.runDeviceTest(message);
        return;
      default:
        return;
    }
  }
  /** 把框选区域（webview 里裁好的 PNG）存到工作区 templates/，并插入找图代码。 */
  async saveTemplate(dataUrl, name) {
    const base64 = dataUrl.replace(/^data:image\/png;base64,/, "");
    if (!base64) {
      vscode2.window.showWarningMessage("AI.js Pro: \u6A21\u677F\u56FE\u7247\u6570\u636E\u4E3A\u7A7A");
      return;
    }
    const folder = vscode2.workspace.workspaceFolders?.[0];
    const safe = sanitizeName(name) || `tpl-${Date.now()}`;
    const relative2 = `templates/${safe}.png`;
    let target;
    if (folder) {
      target = vscode2.Uri.joinPath(folder.uri, "templates", `${safe}.png`);
    } else {
      const picked = await vscode2.window.showSaveDialog({
        defaultUri: vscode2.Uri.file(`${safe}.png`),
        filters: { PNG: ["png"] }
      });
      if (!picked) {
        return;
      }
      target = picked;
    }
    await fs.promises.mkdir(path.dirname(target.fsPath), { recursive: true });
    await fs.promises.writeFile(target.fsPath, Buffer.from(base64, "base64"));
    await this.insert(findImageCode(relative2), "\u627E\u56FE\u4EE3\u7801");
    this.post({ type: "saved", relPath: relative2 });
    vscode2.window.setStatusBarMessage(`AI.js Pro: \u6A21\u677F\u5DF2\u4FDD\u5B58\u5230 ${target.fsPath}`, 5e3);
    this.log(`[\u56FE\u8272] \u6A21\u677F\u5DF2\u4FDD\u5B58\uFF1A${target.fsPath}`);
  }
  /** 把本次截图另存到工作区（默认 shot-<时间>.png）。 */
  async saveShotCopy() {
    if (!this.shotUri) {
      vscode2.window.showWarningMessage("AI.js Pro: \u8FD8\u6CA1\u6709\u622A\u56FE");
      return;
    }
    const folder = vscode2.workspace.workspaceFolders?.[0];
    const name = `shot-${(/* @__PURE__ */ new Date()).toISOString().replace(/[:.]/g, "-").slice(0, 19)}.png`;
    let target;
    if (folder) {
      target = vscode2.Uri.joinPath(folder.uri, name);
    } else {
      const picked = await vscode2.window.showSaveDialog({
        defaultUri: vscode2.Uri.file(name),
        filters: { PNG: ["png"] }
      });
      if (!picked) {
        return;
      }
      target = picked;
    }
    await fs.promises.copyFile(this.shotUri.fsPath, target.fsPath);
    vscode2.window.setStatusBarMessage(`AI.js Pro: \u622A\u56FE\u5DF2\u4FDD\u5B58\u5230 ${target.fsPath}`, 5e3);
  }
};
function capturePreamble() {
  return [
    "// \u622A\u56FE\u6388\u6743\uFF1A\u65E0\u969C\u788D\u4F18\u5148\uFF08\u4E0D\u5F39\u6388\u6743\uFF09\uFF0C\u5931\u8D25\u56DE\u9000\u7CFB\u7EDF\u5F55\u5C4F\u6388\u6743",
    "var __granted = false;",
    "try { __granted = images.requestScreenCapture({ mode: 'accessibility' }); } catch (e) {}",
    "if (!__granted) __granted = requestScreenCapture('portrait');",
    "if (!__granted) throw new Error('\u622A\u56FE\u6388\u6743\u5931\u8D25');",
    "var screen = captureScreen({ mode: 'full' });"
  ];
}
function sixHex(argb) {
  const hex = String(argb).replace(/[^0-9a-fA-F]/g, "");
  const body = hex.length >= 6 ? hex.slice(-6) : hex.padStart(6, "0");
  return "#" + body.toUpperCase();
}
function regionAround(points, width, height) {
  if (points.length === 0 || !width || !height) {
    return void 0;
  }
  const pad = 100;
  const xs = points.map((point) => point.x);
  const ys = points.map((point) => point.y);
  const left = Math.max(0, Math.min(...xs) - pad);
  const top = Math.max(0, Math.min(...ys) - pad);
  const right = Math.min(width, Math.max(...xs) + pad);
  const bottom = Math.min(height, Math.max(...ys) + pad);
  return `[${left}, ${top}, ${right - left}, ${bottom - top}]`;
}
function pickCode(x, y, argb) {
  return [
    `// AI.js Pro \u56FE\u8272\u52A9\u624B\uFF1A\u5355\u70B9\u53D6\u8272 @${x},${y}\uFF08\u9762\u677F\u53D6\u6837 ${sixHex(argb)}\uFF0C\u989C\u8272\u53E3\u5F84=QuickJS\uFF09`,
    ...capturePreamble(),
    `var color = images.pixel(screen, ${x}, ${y});`,
    `console.log(colors.toString(color) + ' @ ${x},${y}');   // #AARRGGBB`,
    "screen.recycle();",
    ""
  ].join("\n");
}
function findColorCode(x, y, argb, width, height) {
  const region = regionAround([{ x, y }], width, height);
  const lines = [
    `// AI.js Pro \u56FE\u8272\u52A9\u624B\uFF1A\u627E\u8272\uFF08\u53D6\u6837\u70B9 ${x},${y} \u2192 ${sixHex(argb)}\uFF0C\u989C\u8272\u53E3\u5F84=QuickJS\uFF09`,
    ...capturePreamble(),
    `var point = images.findColor(screen, '${sixHex(argb)}', {`
  ];
  if (region) {
    lines.push(`    region: ${region},   // x, y, w, h\uFF1A\u53D6\u6837\u70B9\u9644\u8FD1\uFF0C\u6309\u9700\u8C03\u6574`);
  }
  lines.push(
    "    threshold: 24",
    "});",
    "console.log(JSON.stringify(point));   // \u547D\u4E2D {x, y}\uFF0C\u5426\u5219 null",
    "screen.recycle();",
    ""
  );
  return lines.join("\n");
}
function findMultiCode(first, others, width, height) {
  const entries = others.map((item) => `    [${item.dx}, ${item.dy}, '${sixHex(item.argb)}'],`);
  const region = regionAround(
    [
      { x: first.x, y: first.y },
      ...others.map((item) => ({ x: first.x + item.dx, y: first.y + item.dy }))
    ],
    width,
    height
  );
  const lines = [
    `// AI.js Pro \u56FE\u8272\u52A9\u624B\uFF1A\u591A\u70B9\u627E\u8272\uFF08\u57FA\u51C6\u70B9 ${first.x},${first.y} \u2192 ${sixHex(first.argb)}\uFF0C\u5171 ${others.length + 1} \u4E2A\u70B9\uFF09`,
    ...capturePreamble(),
    `var point = images.findMultiColors(screen, '${sixHex(first.argb)}', [`,
    ...entries,
    "], {"
  ];
  if (region) {
    lines.push(`    region: ${region},`);
  }
  lines.push(
    "    threshold: 20",
    "});",
    "console.log(JSON.stringify(point));   // \u547D\u4E2D {x, y}\uFF0C\u5426\u5219 null",
    "screen.recycle();",
    ""
  );
  return lines.join("\n");
}
function findImageCode(relativePath) {
  return [
    "// AI.js Pro \u56FE\u8272\u52A9\u624B\uFF1A\u627E\u56FE\uFF08\u6A21\u677F\u5728\u5DE5\u4F5C\u533A templates/\uFF0C\u7528\u300C\u63A8\u9001\u5E76\u8FD0\u884C\u6574\u4E2A\u5DE5\u7A0B\u300D\u540C\u6B65\u5230\u624B\u673A\uFF09",
    ...capturePreamble(),
    `var template = images.read('${relativePath}');`,
    "var point = images.findImage(screen, template, { threshold: 0.9 });",
    "console.log(JSON.stringify(point));   // \u547D\u4E2D {x, y}\uFF0C\u5426\u5219 null",
    "if (template.recycle) template.recycle();",
    "screen.recycle();",
    ""
  ].join("\n");
}
function stripColorProfile(png) {
  if (png.length < 8 || png.readUInt32BE(0) !== 2303741511) {
    return png;
  }
  const pieces = [png.subarray(0, 8)];
  let offset = 8;
  while (offset + 12 <= png.length) {
    const length = png.readUInt32BE(offset);
    const type = png.toString("ascii", offset + 4, offset + 8);
    const chunk = png.subarray(offset, offset + 12 + length);
    if (type !== "iCCP" && type !== "sBIT" && type !== "gAMA" && type !== "sRGB") {
      pieces.push(chunk);
    }
    offset += 12 + length;
    if (type === "IEND") {
      break;
    }
  }
  return Buffer.concat(pieces);
}
function buildTestScript(payload) {
  const kind = String(payload?.kind ?? "");
  const parsedThreshold = Number(payload?.threshold);
  const threshold = Number.isFinite(parsedThreshold) ? parsedThreshold : 4;
  const header = [
    "// @engine quickjs",
    "// AI.js Pro \u56FE\u8272\u52A9\u624B\u6D4B\u8BD5\u811A\u672C\uFF08\u4E34\u65F6\uFF0C\u8DD1\u5B8C\u5373\u5F03\uFF09",
    'var __aijsOut = function (text) { console.log("AIJS_TEST " + text); };',
    "var __aijsQ = function (text) {",
    '  return String(text).replace(/["\\n\\r]/g, " ").replace(/\\s+/g, " ");',
    "};",
    "var __aijsResult = function (obj) {",
    "  var parts = [];",
    "  for (var key in obj) {",
    "    var value = obj[key];",
    `    parts.push('"' + key + '":' + (typeof value === 'string' ? '"' + __aijsQ(value) + '"' : String(value)));`,
    "  }",
    '  __aijsOut("{" + parts.join(",") + "}");',
    "};",
    `var __aijsFail = function (error) { __aijsResult({ kind: "${kind}", error: String(error) }); };`,
    "var __img = null;",
    "try {",
    "  var __granted = false;",
    '  try { __granted = images.requestScreenCapture({ mode: "accessibility" }); } catch (e0) {}',
    "  if (!__granted) { try { __granted = images.requestScreenCapture(true); } catch (e1) {} }",
    "  __img = captureScreen();",
    "} catch (e) { __aijsFail(e); }",
    "if (__img) {",
    "  try {",
    '    var __size = "?";',
    "    try {",
    '      if (typeof __img.getWidth === "function") __size = __img.getWidth() + "x" + __img.getHeight();',
    '      else if (typeof __img.width === "number") __size = __img.width + "x" + __img.height;',
    "    } catch (e2) {}"
  ];
  const tail = ["  } catch (e) { __aijsFail(e); }", "}"];
  const body = [];
  if (kind === "pick") {
    const x = Math.round(Number(payload.x ?? 0));
    const y = Math.round(Number(payload.y ?? 0));
    const argb = String(payload.argb ?? "");
    body.push(
      `    var __c = images.pixel(__img, ${x}, ${y});`,
      "    var __cs = colors.toString(__c);",
      `    __aijsResult({ kind: 'pick', hit: String(__cs).toLowerCase() === '${argb.toLowerCase()}', x: ${x}, y: ${y}, color: __cs, size: __size });`
    );
  } else if (kind === "findColor") {
    const argb = String(payload.argb ?? "");
    body.push(
      `    var __p = images.findColor(__img, '${argb}', { threshold: ${threshold} });`,
      "    if (__p) { __aijsResult({ kind: 'findColor', hit: true, x: __p.x, y: __p.y, size: __size }); } else { __aijsResult({ kind: 'findColor', hit: false, size: __size }); }"
    );
  } else if (kind === "findMulti") {
    const first = payload.first ?? {};
    const others = Array.isArray(payload.others) ? payload.others : [];
    const entries = others.map(
      (item) => `      [${Math.round(Number(item?.dx ?? 0))}, ${Math.round(Number(item?.dy ?? 0))}, '${String(item?.argb ?? "")}'],`
    );
    body.push(
      "    var __paths = [",
      ...entries,
      "    ];",
      `    var __p = images.findMultiColors(__img, '${String(first.argb ?? "")}', __paths, { threshold: ${threshold} });`,
      "    if (__p) { __aijsResult({ kind: 'findMulti', hit: true, x: __p.x, y: __p.y, size: __size }); } else { __aijsResult({ kind: 'findMulti', hit: false, size: __size }); }"
    );
  } else if (kind === "findImage") {
    const base64 = String(payload.dataUrl ?? "").replace(/^data:image\/png;base64,/, "");
    if (!base64 || base64.length > 4e5) {
      return void 0;
    }
    const imageThreshold = Number.isFinite(Number(payload.imageThreshold)) ? Number(payload.imageThreshold) : 0.9;
    body.push(
      `    var __tpl = images.fromBase64('${base64}');`,
      `    var __p = images.findImage(__img, __tpl, { threshold: ${imageThreshold} });`,
      "    if (__p) { __aijsResult({ kind: 'findImage', hit: true, x: __p.x, y: __p.y, size: __size }); } else { __aijsResult({ kind: 'findImage', hit: false, size: __size }); }"
    );
  } else {
    return void 0;
  }
  return [...header, ...body, ...tail].join("\n");
}
function sanitizeName(name) {
  return name.trim().replace(/\.png$/i, "").replace(/[\\/:*?"<>|\s]+/g, "_").slice(0, 60);
}
function buildHtml() {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<style>
  * { box-sizing: border-box; }
  body { margin: 0; height: 100vh; display: flex; flex-direction: column;
         font-family: var(--vscode-font-family); color: var(--vscode-foreground);
         background: var(--vscode-editor-background); }
  #toolbar { display: flex; flex-wrap: wrap; gap: 6px; padding: 6px 8px; align-items: center;
             border-bottom: 1px solid var(--vscode-panel-border); }
  button { background: var(--vscode-button-secondaryBackground, #3a3d41); color: var(--vscode-button-secondaryForeground, #ccc);
           border: 1px solid transparent; padding: 4px 10px; border-radius: 4px; cursor: pointer; font-size: 12px; }
  button:hover { background: var(--vscode-button-secondaryHoverBackground, #45494e); }
  button.active { background: var(--vscode-button-background, #0e639c); color: var(--vscode-button-foreground, #fff); }
  button:disabled { opacity: 0.45; cursor: default; }
  .sep { width: 1px; height: 20px; background: var(--vscode-panel-border); margin: 0 4px; }
  #stage { position: relative; flex: 1; overflow: hidden; background: #101010; cursor: crosshair; }
  #base { position: absolute; left: 0; top: 0; transform-origin: 0 0; image-rendering: pixelated; }
  #overlay { position: absolute; left: 0; top: 0; pointer-events: none; }
  #status { padding: 5px 10px; font-size: 12px; border-top: 1px solid var(--vscode-panel-border);
            display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }
  .chip { display: inline-flex; align-items: center; gap: 5px; }
  .swatch { display: inline-block; width: 14px; height: 14px; border: 1px solid #888; border-radius: 2px;
            vertical-align: middle; }
  code { font-family: var(--vscode-editor-font-family); }
  #toast { position: absolute; left: 50%; bottom: 18px; transform: translateX(-50%);
           background: rgba(0,0,0,0.78); padding: 6px 14px; border-radius: 14px; font-size: 12px;
           opacity: 0; transition: opacity .18s; pointer-events: none; }
  #hint { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
          color: #888; font-size: 14px; }
</style>
</head>
<body>
<div id="toolbar">
  <button id="modePick" class="active" title="\u5355\u51FB\u53D6\u8272\u5E76\u56FA\u5B9A\u8BE5\u70B9\uFF0C\u62D6\u62FD\u5E73\u79FB">\u53D6\u8272</button>
  <button id="modeRegion" title="\u62D6\u62FD\u6846\u9009\u533A\u57DF">\u6846\u9009</button>
  <button id="modeMulti" title="\u4F9D\u6B21\u70B9\u51FB\u591A\u4E2A\u70B9\uFF0C\u751F\u6210\u591A\u70B9\u627E\u8272">\u591A\u70B9</button>
  <span class="sep"></span>
  <button id="zoomOut">\u2212</button>
  <button id="zoomFit">\u9002\u5E94</button>
  <button id="zoom100">100%</button>
  <button id="zoomIn">\uFF0B</button>
  <span class="sep"></span>
  <button id="copyColor" disabled>\u590D\u5236\u989C\u8272</button>
  <button id="pickCode" disabled>\u63D2\u5165\u53D6\u8272\u4EE3\u7801</button>
  <button id="findColor" disabled>\u63D2\u5165\u627E\u8272\u4EE3\u7801</button>
  <button id="pickClear" disabled>\u6E05\u9664\u56FA\u5B9A\u70B9</button>
  <button id="regionCoords" disabled>\u590D\u5236\u533A\u57DF\u5750\u6807</button>
  <button id="template" disabled>\u4FDD\u5B58\u6A21\u677F\u5E76\u63D2\u5165\u627E\u56FE\u4EE3\u7801</button>
  <button id="multiClear" disabled>\u6E05\u7A7A\u70B9</button>
  <button id="multiUndo" disabled>\u64A4\u9500\u4E00\u70B9</button>
  <button id="multiCode" disabled>\u63D2\u5165\u591A\u70B9\u627E\u8272\u4EE3\u7801</button>
  <span class="sep"></span>
  <button id="testPick" disabled>\u6D4B\u8BD5\u53D6\u8272</button>
  <button id="testFindColor" disabled>\u6D4B\u8BD5\u627E\u8272</button>
  <button id="testFindMulti" disabled>\u6D4B\u8BD5\u591A\u70B9</button>
  <button id="testFindImage" disabled>\u6D4B\u8BD5\u627E\u56FE</button>
  <span class="sep"></span>
  <button id="refresh">\u91CD\u65B0\u622A\u56FE</button>
  <button id="saveShot" disabled>\u622A\u56FE\u53E6\u5B58\u5230\u5DE5\u4F5C\u533A</button>
</div>
<div id="stage">
  <canvas id="base"></canvas>
  <canvas id="overlay"></canvas>
  <div id="hint">\u6B63\u5728\u83B7\u53D6\u624B\u673A\u622A\u56FE\u2026\uFF08\u624B\u673A\u9700\u5F00\u542F AI.js Pro \u65E0\u969C\u788D\u670D\u52A1\uFF09</div>
  <div id="toast"></div>
</div>
<div id="status">
  <span class="chip">\u5750\u6807 <code id="pos">-</code></span>
  <span class="chip">\u56FA\u5B9A <code id="pinpos">-</code></span>
  <span class="chip"><span class="swatch" id="swatch"></span><code id="argb">-</code></span>
  <span class="chip"><code id="rgbhex">-</code></span>
  <span class="chip">int <code id="intval">-</code></span>
  <span class="chip">rgb <code id="rgbval">-</code></span>
  <span class="chip">\u60AC\u505C <span class="swatch" id="hoverswatch"></span><code id="hovercolor">-</code></span>
  <span class="chip">\u6D4B\u8BD5 <code id="test">-</code></span>
  <span class="chip">\u591A\u70B9 <code id="pointcount">0</code></span>
  <span class="chip">\u50CF\u7D20\u53E3\u5F84\uFF1A\u539F\u59CB\u503C\uFF08\u4E0E QuickJS \u811A\u672C\u4E00\u81F4\uFF09</span>
  <span class="chip" id="modehint"></span>
</div>
<script>
  var vscode = acquireVsCodeApi();
  var $ = function (id) { return document.getElementById(id); };
  var base = $('base'), overlay = $('overlay'), stage = $('stage');
  var bctx = base.getContext('2d', { willReadFrequently: true });
  var octx = overlay.getContext('2d');
  var W = 0, H = 0;                 // \u622A\u56FE\u539F\u59CB\u50CF\u7D20\u5C3A\u5BF8
  var zoom = 1, tx = 0, ty = 0;     // \u89C6\u56FE\u53D8\u6362\uFF1A translate(tx,ty) scale(zoom)
  var mode = 'pick';
  var cursor = null;                // {x,y,vx,vy} \u60AC\u505C\u4F4D\u7F6E\uFF08\u56FE\u50CF\u5750\u6807 + \u89C6\u56FE\u5750\u6807\uFF09
  var color = null;                 // \u5F53\u524D\u4E3B\u663E\u793A\u989C\u8272 {r,g,b,a}\uFF08\u56FA\u5B9A\u70B9\u4F18\u5148\uFF0C\u5426\u5219\u60AC\u505C\uFF09
  var pen = null;                   // \u56FA\u5B9A\u70B9\uFF08\u5355\u51FB\u53D6\u8272\u540E\u9501\u4F4F\uFF09{x,y,r,g,b,a}
  var region = null;                // \u6846\u9009 {x,y,w,h} \u56FE\u50CF\u5750\u6807
  var points = [];                  // \u591A\u70B9 [{x,y,r,g,b,a}]
  var testHit = null;               // \u6D4B\u8BD5\u547D\u4E2D\u7684\u5750\u6807 {x,y}
  var pressed = false, panned = false, dragStart = null, panStart = null;
  var lastTemplate = null;

  function toast(text) {
    var el = $('toast');
    el.textContent = text;
    el.style.opacity = '1';
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.style.opacity = '0'; }, 1400);
  }
  function hex2(n) { return ('0' + n.toString(16)).slice(-2); }
  function argbOf(c) { return '#' + hex2(c.a) + hex2(c.r) + hex2(c.g) + hex2(c.b); }
  function rgbHexOf(c) { return '#' + hex2(c.r) + hex2(c.g) + hex2(c.b); }
  function intOf(c) { return ((c.a << 24) | (c.r << 16) | (c.g << 8) | c.b) >>> 0; }

  function applyTransform() {
    base.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + zoom + ')';
    draw();
  }
  function fit() {
    var rect = stage.getBoundingClientRect();
    if (!W || !H) return;
    zoom = Math.min(rect.width / W, rect.height / H);
    tx = (rect.width - W * zoom) / 2;
    ty = (rect.height - H * zoom) / 2;
    applyTransform();
  }
  function resizeOverlay() {
    var rect = stage.getBoundingClientRect();
    overlay.width = rect.width;
    overlay.height = rect.height;
    draw();
  }
  function toImage(clientX, clientY) {
    var rect = stage.getBoundingClientRect();
    var vx = clientX - rect.left, vy = clientY - rect.top;
    return { x: Math.floor((vx - tx) / zoom), y: Math.floor((vy - ty) / zoom), vx: vx, vy: vy };
  }
  function toView(x, y) { return { x: x * zoom + tx, y: y * zoom + ty }; }

  function pixel(x, y) {
    if (x < 0 || y < 0 || x >= W || y >= H) return null;
    var d = bctx.getImageData(x, y, 1, 1).data;
    return { r: d[0], g: d[1], b: d[2], a: d[3] };
  }

  function setColor(c, x, y) {
    color = c;
    if (!c) { $('swatch').style.background = 'transparent'; return; }
    $('swatch').style.background = rgbHexOf(c);
    $('argb').textContent = argbOf(c);
    $('rgbhex').textContent = rgbHexOf(c);
    $('intval').textContent = intOf(c) + ' (0x' + intOf(c).toString(16) + ')';
    $('rgbval').textContent = c.r + ',' + c.g + ',' + c.b;
    $('copyColor').disabled = false;
    $('pickCode').disabled = false;
    $('findColor').disabled = false;
    $('testPick').disabled = false;
    $('testFindColor').disabled = false;
  }

  /** \u60AC\u505C\u8272\u5355\u72EC\u663E\u793A\uFF0C\u4E0D\u5F71\u54CD\u56FA\u5B9A\u70B9\u3002 */
  function setHover(c) {
    if (!c) {
      $('hoverswatch').style.background = 'transparent';
      $('hovercolor').textContent = '-';
      return;
    }
    $('hoverswatch').style.background = rgbHexOf(c);
    $('hovercolor').textContent = argbOf(c);
  }

  /** \u5355\u51FB\u53D6\u8272\uFF1A\u628A\u989C\u8272\u56FA\u5B9A\u4E0B\u6765\uFF08\u79FB\u52A8\u9F20\u6807\u4E0D\u518D\u6539\u5B83\uFF09\u3002 */
  function pinPoint(x, y, c) {
    pen = { x: x, y: y, r: c.r, g: c.g, b: c.b, a: c.a };
    setColor(pen, x, y);
    $('pinpos').textContent = x + ', ' + y;
    $('pickClear').disabled = false;
  }

  function clearPin() {
    pen = null;
    $('pinpos').textContent = '-';
    $('pickClear').disabled = true;
    var live = cursor ? pixel(cursor.x, cursor.y) : null;
    setColor(live, cursor ? cursor.x : 0, cursor ? cursor.y : 0);
    draw();
  }

  /** \u64CD\u4F5C\uFF08\u590D\u5236/\u63D2\u4EE3\u7801/\u6D4B\u8BD5\uFF09\u4F18\u5148\u7528\u56FA\u5B9A\u70B9\uFF0C\u5426\u5219\u7528\u60AC\u505C\u70B9\u3002 */
  function actionPoint() {
    if (pen) return { x: pen.x, y: pen.y };
    if (cursor && cursor.x >= 0 && cursor.y >= 0 && cursor.x < W && cursor.y < H) {
      return { x: cursor.x, y: cursor.y };
    }
    return null;
  }

  function actionColor() {
    return pen ? pen : color;
  }

  function setMode(next) {
    mode = next;
    $('modePick').className = next === 'pick' ? 'active' : '';
    $('modeRegion').className = next === 'region' ? 'active' : '';
    $('modeMulti').className = next === 'multi' ? 'active' : '';
    $('modehint').textContent = next === 'pick' ? '\u5355\u51FB\u53D6\u8272\uFF08\u70B9\u51FB\u540E\u56FA\u5B9A\uFF09/ \u62D6\u62FD\u5E73\u79FB'
      : next === 'region' ? '\u62D6\u62FD\u6846\u9009\u533A\u57DF' : '\u4F9D\u6B21\u70B9\u51FB\u53D6\u8272\u70B9\uFF08\u9996\u70B9\u4E3A\u57FA\u51C6\uFF1B\u62D6\u62FD\u5E73\u79FB\uFF09';
    stage.style.cursor = next === 'region' ? 'crosshair' : (next === 'multi' ? 'copy' : 'crosshair');
    draw();
  }

  function draw() {
    if (!octx) return;
    octx.clearRect(0, 0, overlay.width, overlay.height);
    // \u6846\u9009
    if (region) {
      var p1 = toView(region.x, region.y);
      var p2 = toView(region.x + region.w, region.y + region.h);
      octx.fillStyle = 'rgba(80,160,255,0.20)';
      octx.fillRect(p1.x, p1.y, p2.x - p1.x, p2.y - p1.y);
      octx.strokeStyle = 'rgba(120,190,255,0.95)';
      octx.lineWidth = 1;
      octx.strokeRect(p1.x + 0.5, p1.y + 0.5, p2.x - p1.x - 1, p2.y - p1.y - 1);
    }
    // \u591A\u70B9\u6807\u8BB0
    for (var i = 0; i < points.length; i++) {
      var v = toView(points[i].x, points[i].y);
      octx.strokeStyle = i === 0 ? '#ff5252' : '#4caf50';
      octx.lineWidth = 2;
      octx.beginPath();
      octx.arc(v.x + zoom / 2, v.y + zoom / 2, Math.max(5, zoom * 1.2), 0, Math.PI * 2);
      octx.stroke();
      octx.fillStyle = i === 0 ? '#ff5252' : '#4caf50';
      octx.font = '11px monospace';
      octx.fillText(String(i), v.x + 7, v.y - 4);
    }
    // \u56FA\u5B9A\u70B9\u6807\u8BB0\uFF08\u5355\u51FB\u53D6\u8272\u9501\u4F4F\u7684\u70B9\uFF09
    if (pen) {
      var pv = toView(pen.x, pen.y);
      octx.strokeStyle = '#ffd54f';
      octx.lineWidth = 2;
      octx.strokeRect(pv.x - 1, pv.y - 1, zoom + 2, zoom + 2);
      octx.beginPath();
      octx.arc(pv.x + zoom / 2, pv.y + zoom / 2, Math.max(7, zoom * 1.4), 0, Math.PI * 2);
      octx.stroke();
      octx.fillStyle = '#ffd54f';
      octx.font = '12px monospace';
      octx.fillText(argbOf(pen) + '  ' + pen.x + ',' + pen.y, pv.x + 12, pv.y - 8);
    }
    // \u6D4B\u8BD5\u547D\u4E2D\u6807\u8BB0
    if (testHit) {
      var th = toView(testHit.x, testHit.y);
      octx.strokeStyle = '#00e5ff';
      octx.lineWidth = 2;
      octx.setLineDash([4, 3]);
      octx.beginPath();
      octx.arc(th.x + zoom / 2, th.y + zoom / 2, Math.max(9, zoom * 2), 0, Math.PI * 2);
      octx.stroke();
      octx.setLineDash([]);
      octx.fillStyle = '#00e5ff';
      octx.font = '12px monospace';
      octx.fillText('\u6D4B\u8BD5 ' + testHit.x + ',' + testHit.y, th.x + 12, th.y - 6);
    }
    // \u5341\u5B57\u7EBF + \u653E\u5927\u955C
    if (cursor && cursor.x >= 0 && cursor.y >= 0 && cursor.x < W && cursor.y < H) {
      octx.strokeStyle = 'rgba(255,80,80,0.85)';
      octx.lineWidth = 1;
      octx.beginPath();
      octx.moveTo(cursor.vx, 0); octx.lineTo(cursor.vx, overlay.height);
      octx.moveTo(0, cursor.vy); octx.lineTo(overlay.width, cursor.vy);
      octx.stroke();
      drawMagnifier();
    }
  }

  function drawMagnifier() {
    var N = 11, Z = 12, pad = 14, size = N * Z;
    var mx = cursor.vx + pad, my = cursor.vy + pad;
    if (mx + size > overlay.width) mx = cursor.vx - size - pad;
    if (my + size > overlay.height) my = cursor.vy - size - pad;
    if (mx < 0 || my < 0) return;
    octx.save();
    octx.imageSmoothingEnabled = false;
    var sx = cursor.x - Math.floor(N / 2), sy = cursor.y - Math.floor(N / 2);
    octx.fillStyle = 'rgba(0,0,0,0.75)';
    octx.fillRect(mx - 1, my - 1, size + 2, size + 2);
    octx.drawImage(base, sx, sy, N, N, mx, my, size, size);
    octx.strokeStyle = 'rgba(255,255,255,0.12)';
    octx.lineWidth = 1;
    for (var i = 1; i < N; i++) {
      octx.beginPath(); octx.moveTo(mx + i * Z, my); octx.lineTo(mx + i * Z, my + size); octx.stroke();
      octx.beginPath(); octx.moveTo(mx, my + i * Z); octx.lineTo(mx + size, my + i * Z); octx.stroke();
    }
    var c = Math.floor(N / 2);
    octx.strokeStyle = '#ff5252';
    octx.lineWidth = 2;
    octx.strokeRect(mx + c * Z + 1, my + c * Z + 1, Z - 2, Z - 2);
    octx.restore();
  }

  // ---- \u4EA4\u4E92 ----
  stage.addEventListener('mousemove', function (e) {
    var p = toImage(e.clientX, e.clientY);
    cursor = p;
    $('pos').textContent = (p.x >= 0 && p.y >= 0 && p.x < W && p.y < H) ? p.x + ', ' + p.y : '-';
    if (pressed) {
      if (mode === 'region' && dragStart) {
        var x1 = Math.min(dragStart.x, p.x), y1 = Math.min(dragStart.y, p.y);
        var x2 = Math.max(dragStart.x, p.x), y2 = Math.max(dragStart.y, p.y);
        region = { x: Math.max(0, x1), y: Math.max(0, y1),
                   w: Math.min(W, x2) - Math.max(0, x1), h: Math.min(H, y2) - Math.max(0, y1) };
        updateRegionButtons();
      } else if (panStart) {
        var moved = Math.abs(e.clientX - panStart.cx) + Math.abs(e.clientY - panStart.cy);
        // \u5148\u8FC7\u4E86\u9608\u503C\u624D\u7B97\u5E73\u79FB\uFF08\u5426\u5219\u8F7B\u5FAE\u62D6\u52A8\u4F1A\u628A\u5355\u51FB\u5403\u6389\uFF09
        if (!panned && moved > 8) panned = true;
        if (panned) {
          tx = panStart.tx + (e.clientX - panStart.cx);
          ty = panStart.ty + (e.clientY - panStart.cy);
          applyTransform();
        }
        return;
      }
    }
    if (mode !== 'region') {
      var live = pixel(p.x, p.y);
      setHover(live);
      if (!pen) setColor(live, p.x, p.y);
    }
    draw();
  });
  stage.addEventListener('mousedown', function (e) {
    if (e.button !== 0) return;
    pressed = true;
    panned = false;
    var p = toImage(e.clientX, e.clientY);
    if (mode === 'region') {
      dragStart = { x: Math.max(0, Math.min(W - 1, p.x)), y: Math.max(0, Math.min(H - 1, p.y)) };
      region = { x: dragStart.x, y: dragStart.y, w: 0, h: 0 };
    } else {
      panStart = { cx: e.clientX, cy: e.clientY, tx: tx, ty: ty };
    }
    e.preventDefault();
  });
  stage.addEventListener('mouseup', function (e) {
    if (!pressed) return;
    pressed = false;
    var p = toImage(e.clientX, e.clientY);
    // \u6309\u4F4F\u540E\u7684\u603B\u4F4D\u79FB\u624D\u662F\u5224\u636E\uFF1A\u5FAE\u5C0F\u6296\u52A8\u4ECD\u7B97\u5355\u51FB\uFF08\u591A\u70B9\u6A21\u5F0F\u4E0B\u70B9\u624D\u4E0D\u4F1A\u4E22\uFF09
    var moved = panStart ? Math.abs(e.clientX - panStart.cx) + Math.abs(e.clientY - panStart.cy) : 0;
    var wasPan = panned || moved > 8;
    panStart = null;
    if (mode === 'region') {
      dragStart = null;
      if (region && (region.w < 2 || region.h < 2)) { region = null; }
      updateRegionButtons();
    } else if (!wasPan) {
      var c = pixel(p.x, p.y);
      if (c) {
        pinPoint(p.x, p.y, c);
        if (mode === 'multi') {
          points.push({ x: p.x, y: p.y, r: c.r, g: c.g, b: c.b, a: c.a });
          updateMultiButtons();
          toast('\u5DF2\u6DFB\u52A0\u70B9 ' + points.length + '\uFF08\u9996\u70B9\u4E3A\u57FA\u51C6\uFF09');
        } else {
          toast('\u5DF2\u56FA\u5B9A ' + argbOf(c) + ' @ ' + p.x + ',' + p.y + '\uFF08\u79FB\u52A8\u9F20\u6807\u4E0D\u518D\u6539\u53D8\uFF09');
        }
      }
    }
    draw();
  });
  stage.addEventListener('mouseleave', function () { cursor = null; setHover(null); draw(); });
  stage.addEventListener('wheel', function (e) {
    var rect = stage.getBoundingClientRect();
    var cx = e.clientX - rect.left, cy = e.clientY - rect.top;
    var k = e.deltaY < 0 ? 1.25 : 0.8;
    var nz = Math.min(8, Math.max(0.1, zoom * k));
    tx = cx - (cx - tx) * (nz / zoom);
    ty = cy - (cy - ty) * (nz / zoom);
    zoom = nz;
    applyTransform();
    e.preventDefault();
  }, { passive: false });
  window.addEventListener('resize', resizeOverlay);

  // ---- \u6309\u94AE ----
  $('modePick').onclick = function () { setMode('pick'); };
  $('modeRegion').onclick = function () { setMode('region'); };
  $('modeMulti').onclick = function () { setMode('multi'); };
  $('zoomIn').onclick = function () { zoom = Math.min(8, zoom * 1.25); applyTransform(); };
  $('zoomOut').onclick = function () { zoom = Math.max(0.1, zoom / 1.25); applyTransform(); };
  $('zoom100').onclick = function () { zoom = 1; tx = 0; ty = 0; applyTransform(); };
  $('zoomFit').onclick = fit;
  $('refresh').onclick = function () { vscode.postMessage({ type: 'refresh' }); };
  $('saveShot').onclick = function () { vscode.postMessage({ type: 'saveShot' }); };
  $('copyColor').onclick = function () {
    var c = actionColor();
    if (c) vscode.postMessage({ type: 'copy', text: argbOf(c) });
  };
  $('pickClear').onclick = clearPin;
  $('pickCode').onclick = function () {
    var p = actionPoint();
    var c = actionColor();
    if (!p || !c) return;
    vscode.postMessage({ type: 'insertPick', x: p.x, y: p.y, argb: argbOf(c) });
  };
  $('findColor').onclick = function () {
    var p = actionPoint();
    var c = actionColor();
    if (!p || !c) return;
    vscode.postMessage({ type: 'insertFindColor', x: p.x, y: p.y, argb: argbOf(c) });
  };
  $('regionCoords').onclick = function () {
    if (!region) return;
    var text = region.x + ', ' + region.y + ', ' + (region.x + region.w) + ', ' + (region.y + region.h);
    vscode.postMessage({ type: 'copy', text: text });
  };
  $('template').onclick = function () {
    if (!region || region.w < 2 || region.h < 2) return;
    var off = document.createElement('canvas');
    off.width = region.w; off.height = region.h;
    off.getContext('2d').drawImage(base, region.x, region.y, region.w, region.h, 0, 0, region.w, region.h);
    vscode.postMessage({
      type: 'saveTemplate',
      dataUrl: off.toDataURL('image/png'),
      name: 'tpl-' + region.x + '_' + region.y + '_' + region.w + 'x' + region.h,
    });
  };
  $('multiClear').onclick = function () {
    points = [];
    updateMultiButtons();
    draw();
    toast('\u5DF2\u6E05\u7A7A\u70B9');
  };
  $('multiUndo').onclick = function () {
    var removed = points.pop();
    if (removed && pen && pen.x === removed.x && pen.y === removed.y) {
      pen = null;
      $('pinpos').textContent = '-';
      $('pickClear').disabled = true;
    }
    updateMultiButtons();
    draw();
    toast('\u5DF2\u64A4\u9500\u4E00\u70B9\uFF0C\u5269 ' + points.length + ' \u4E2A');
  };
  $('multiCode').onclick = function () {
    if (points.length < 2) { toast('\u81F3\u5C11\u9700\u8981 2 \u4E2A\u70B9'); return; }
    var first = points[0];
    var others = [];
    for (var i = 1; i < points.length; i++) {
      others.push({ dx: points[i].x - first.x, dy: points[i].y - first.y,
                    argb: argbOf(points[i]) });
    }
    vscode.postMessage({ type: 'insertFindMulti',
      first: { x: first.x, y: first.y, argb: argbOf(first) }, others: others });
  };

  // ---- \u6D4B\u8BD5\uFF08\u5728\u624B\u673A\u4E0A\u91CD\u65B0\u622A\u56FE\u5E76\u771F\u8DD1\u4E00\u6B21\uFF0C\u56DE\u4F20\u547D\u4E2D\u5750\u6807\uFF09----
  $('testPick').onclick = function () {
    var p = actionPoint();
    var c = actionColor();
    if (!p || !c) return;
    setTestText('\u6D4B\u8BD5\u4E2D\u2026');
    vscode.postMessage({ type: 'test', kind: 'pick', x: p.x, y: p.y, argb: argbOf(c) });
  };
  $('testFindColor').onclick = function () {
    var c = actionColor();
    if (!c) return;
    setTestText('\u6D4B\u8BD5\u4E2D\u2026');
    vscode.postMessage({ type: 'test', kind: 'findColor', argb: argbOf(c), threshold: 4 });
  };
  $('testFindMulti').onclick = function () {
    if (points.length < 2) { toast('\u81F3\u5C11\u9700\u8981 2 \u4E2A\u70B9'); return; }
    var first = points[0];
    var others = [];
    for (var i = 1; i < points.length; i++) {
      others.push({ dx: points[i].x - first.x, dy: points[i].y - first.y,
                    argb: argbOf(points[i]) });
    }
    setTestText('\u6D4B\u8BD5\u4E2D\u2026');
    vscode.postMessage({ type: 'test', kind: 'findMulti',
      first: { x: first.x, y: first.y, argb: argbOf(first) }, others: others, threshold: 4 });
  };
  $('testFindImage').onclick = function () {
    if (!region || region.w < 2 || region.h < 2) return;
    var off = document.createElement('canvas');
    off.width = region.w; off.height = region.h;
    off.getContext('2d').drawImage(base, region.x, region.y, region.w, region.h, 0, 0, region.w, region.h);
    setTestText('\u6D4B\u8BD5\u4E2D\u2026');
    vscode.postMessage({ type: 'test', kind: 'findImage',
      dataUrl: off.toDataURL('image/png'), imageThreshold: 0.9 });
  };

  function setTestText(text) { $('test').textContent = text; }

  function updateRegionButtons() {
    var ok = region && region.w >= 2 && region.h >= 2;
    $('regionCoords').disabled = !ok;
    $('template').disabled = !ok;
    $('testFindImage').disabled = !ok;
  }
  function updateMultiButtons() {
    $('multiClear').disabled = points.length === 0;
    $('multiUndo').disabled = points.length === 0;
    $('multiCode').disabled = points.length < 2;
    $('testFindMulti').disabled = points.length < 2;
    $('pointcount').textContent = points.length;
  }

  // ---- \u4E0E\u6269\u5C55\u901A\u4FE1 ----
  window.addEventListener('message', function (event) {
    var msg = event.data || {};
    if (msg.type === 'busy') {
      $('refresh').disabled = !!msg.on;
      if (msg.on) { $('hint').textContent = '\u6B63\u5728\u83B7\u53D6\u624B\u673A\u622A\u56FE\u2026'; $('hint').style.display = 'flex'; }
      return;
    }
    if (msg.type === 'error') {
      $('hint').textContent = '\u622A\u56FE\u5931\u8D25\uFF1A' + msg.message;
      $('hint').style.display = 'flex';
      toast('\u622A\u56FE\u5931\u8D25\uFF1A' + msg.message);
      return;
    }
    if (msg.type === 'saved') {
      toast('\u6A21\u677F\u5DF2\u4FDD\u5B58\uFF1A' + msg.relPath);
      lastTemplate = msg.relPath;
      return;
    }
    if (msg.type === 'testBusy') {
      $('testPick').disabled = !!msg.on;
      $('testFindColor').disabled = !!msg.on;
      $('testFindMulti').disabled = !!msg.on || points.length < 2;
      $('testFindImage').disabled = !!msg.on || !(region && region.w >= 2 && region.h >= 2);
      return;
    }
    if (msg.type === 'testResult') {
      var r = msg.result || {};
      if (r.error) {
        setTestText((r.kind || '?') + ' \u9519\u8BEF\uFF1A' + r.error);
        testHit = null;
        toast('\u6D4B\u8BD5\u5931\u8D25\uFF1A' + r.error);
      } else if (r.hit) {
        setTestText(
          (r.kind || '?') +
            ' \u547D\u4E2D (' + r.x + ', ' + r.y + ')' +
            (r.size && r.size !== '?' ? '  \u5E27 ' + r.size : ''),
        );
        testHit = { x: r.x, y: r.y };
        toast('\u547D\u4E2D (' + r.x + ', ' + r.y + ')');
      } else {
        setTestText((r.kind || '?') + ' \u672A\u547D\u4E2D\uFF08\u9608\u503C 4\uFF09');
        testHit = null;
        toast('\u672A\u547D\u4E2D');
      }
      draw();
      return;
    }
    if (msg.type === 'shot') {
      var image = new Image();
      image.onload = function () {
        W = image.naturalWidth; H = image.naturalHeight;
        base.width = W; base.height = H;
        bctx.drawImage(image, 0, 0);
        $('hint').style.display = 'none';
        $('saveShot').disabled = false;
        // \u65B0\u622A\u56FE \u2192 \u65E7\u7684\u56FA\u5B9A\u70B9/\u6D4B\u8BD5\u6807\u8BB0\u5168\u90E8\u4F5C\u5E9F
        pen = null;
        testHit = null;
        $('pinpos').textContent = '-';
        $('pickClear').disabled = true;
        setTestText('-');
        setHover(null);
        fit();
        resizeOverlay();
        toast('\u622A\u56FE ' + W + '\xD7' + H + '\uFF08' + msg.device + '\uFF09');
      };
      image.src = msg.uri;
      return;
    }
  });

  setMode('pick');
  resizeOverlay();
  vscode.postMessage({ type: 'ready' });
</script>
</body>
</html>
`;
}

// src/debug.ts
var fs2 = __toESM(require("fs"));
var path2 = __toESM(require("path"));
var vscode3 = __toESM(require("vscode"));
var AijsproDebugSession = class {
  constructor(manager2, output) {
    this.manager = manager2;
    this.log = (message) => {
      output.appendLine(message);
      const sink = process.env.AIJSPRO_E2E_DEBUG_LOG;
      if (sink) {
        try {
          require("fs").appendFileSync(sink, `${message}
`, "utf8");
        } catch {
        }
      }
    };
  }
  emitter = new vscode3.EventEmitter();
  onDidSendMessage = this.emitter.event;
  connection;
  programPath;
  scriptName = "script.js";
  scriptText = "";
  breakpoints = [];
  launchRequested = false;
  configured = false;
  running = false;
  /** 最近一次 stopped 事件的调用栈缓存（stackTrace 请求用）。 */
  frames = [];
  disposables = [];
  log;
  dispose() {
    for (const item of this.disposables.splice(0)) {
      item.dispose();
    }
    if (this.running) {
      void this.connection?.debugTerminate();
    }
    this.connection = void 0;
    this.emitter.dispose();
  }
  handleMessage(message) {
    const request = message;
    if (request?.type !== "request" || typeof request.command !== "string") {
      return;
    }
    void this.dispatch(request.command, request.arguments ?? {}, request.seq ?? 0);
  }
  // ---- 请求分发 ----
  async dispatch(command, args, seq) {
    try {
      switch (command) {
        case "initialize":
          this.respond(seq, command, {
            supportsConfigurationDoneRequest: true,
            supportsEvaluateForHovers: true,
            supportsTerminateRequest: true,
            supportsSetVariable: false,
            supportsRestartRequest: false,
            exceptionBreakpointFilters: []
          });
          this.event("initialized");
          return;
        case "launch":
          await this.onLaunch(args);
          this.respond(seq, command);
          return;
        case "setBreakpoints":
          await this.onSetBreakpoints(args);
          this.respond(seq, command, {
            breakpoints: (args?.breakpoints ?? []).map((item) => ({
              verified: true,
              line: item.line
            }))
          });
          return;
        case "setFunctionBreakpoints":
          this.respond(seq, command, { breakpoints: [] });
          return;
        case "setExceptionBreakpoints":
          this.respond(seq, command);
          return;
        case "configurationDone":
          await this.onConfigurationDone();
          this.respond(seq, command);
          return;
        case "threads":
          this.respond(seq, command, {
            threads: [{ id: 1, name: "main\uFF08\u624B\u673A\u7AEF\u811A\u672C\uFF09" }]
          });
          return;
        case "stackTrace":
          this.respond(seq, command, { stackFrames: this.stackFrames(), totalFrames: this.frames.length });
          return;
        case "scopes":
          this.respond(seq, command, {
            scopes: this.running ? [] : [{ name: "\u4F5C\u7528\u57DF", variablesReference: 1, expensive: false }]
          });
          return;
        case "variables":
          await this.onVariables(args, seq, command);
          return;
        case "evaluate":
          await this.onEvaluate(args, seq, command);
          return;
        case "continue":
        case "next":
        case "stepIn":
        case "stepOut":
          await this.onResume(command, seq);
          return;
        case "pause":
          this.respond(seq, command, void 0, "Rhino \u8C03\u8BD5\u5668\u4E0D\u652F\u6301\u6682\u505C\u6B63\u5728\u8FD0\u884C\u7684\u811A\u672C");
          return;
        case "terminate":
          await this.connection?.debugTerminate();
          this.respond(seq, command);
          return;
        case "disconnect":
          await this.connection?.debugTerminate();
          this.running = false;
          this.respond(seq, command);
          return;
        default:
          this.respond(seq, command, void 0, `\u4E0D\u652F\u6301\u7684\u8C03\u8BD5\u8BF7\u6C42\uFF1A${command}`);
          return;
      }
    } catch (error) {
      this.respond(seq, command, void 0, error.message);
    }
  }
  // ---- 各请求实现 ----
  async onLaunch(args) {
    let candidate = args.program;
    if (!candidate || candidate === "${file}") {
      const editor = vscode3.window.activeTextEditor;
      if (editor && editor.document.uri.scheme === "file") {
        candidate = editor.document.fileName;
      }
    }
    if (!candidate || candidate === "${file}") {
      throw new Error("\u6CA1\u6709\u53EF\u8C03\u8BD5\u7684\u811A\u672C\uFF1A\u8BF7\u5728 launch.json \u91CC\u6307\u5B9A program\uFF0C\u6216\u5148\u6253\u5F00\u4E00\u4E2A .js \u6587\u4EF6");
    }
    this.programPath = candidate;
    this.scriptText = await fs2.promises.readFile(candidate, "utf8");
    this.scriptName = path2.basename(candidate);
    this.launchRequested = true;
    this.connection = await this.resolveConnection(args.host);
    this.attachConnection(this.connection);
    this.log(`[debug] ready: ${this.scriptName} @ ${this.connection.label}`);
  }
  async resolveConnection(host) {
    const active = this.manager.activeConnection;
    if (active?.attached) {
      return active;
    }
    if (host) {
      this.log(`[debug] \u6B63\u5728\u8FDE\u63A5 ${host} \u2026`);
      await this.manager.connectTo(host);
      const deadline = Date.now() + 1e4;
      while (Date.now() < deadline) {
        const current = this.manager.activeConnection;
        if (current?.attached) {
          return current;
        }
        await new Promise((resolve) => setTimeout(resolve, 250));
      }
      throw new Error(`\u8FDE\u63A5 ${host} \u8D85\u65F6\uFF08\u8BF7\u786E\u8BA4\u624B\u673A\u4FA7\u5DF2\u6253\u5F00\u300C\u7535\u8111\u8FDE\u63A5 \u2192 \u670D\u52A1\u5668\u6A21\u5F0F\u300D\uFF09`);
    }
    const picked = await this.manager.pickConnection();
    if (!picked?.attached) {
      throw new Error("\u6CA1\u6709\u53EF\u7528\u7684\u8BBE\u5907\u8FDE\u63A5\uFF1A\u8BF7\u5148\u8FDE\u63A5\u624B\u673A\uFF0C\u6216\u5728 launch.json \u91CC\u914D\u7F6E host");
    }
    return picked;
  }
  attachConnection(connection) {
    for (const item of this.disposables.splice(0)) {
      item.dispose();
    }
    const onDebug = (event) => this.onDeviceEvent(event);
    const onLog = (line) => {
      if (this.running) {
        this.event("output", { category: "stdout", output: `${line}
` });
      }
    };
    const onClosed = () => {
      if (this.running || this.launchRequested) {
        this.event("terminated");
        this.event("output", { category: "console", output: "\u8BBE\u5907\u8FDE\u63A5\u5DF2\u65AD\u5F00\n" });
        this.running = false;
      }
    };
    connection.on("debugEvent", onDebug);
    connection.on("log", onLog);
    connection.on("closed", onClosed);
    this.disposables.push(
      new vscode3.Disposable(() => {
        connection.removeListener("debugEvent", onDebug);
        connection.removeListener("log", onLog);
        connection.removeListener("closed", onClosed);
      })
    );
    this.connection = connection;
  }
  async onSetBreakpoints(args) {
    const lines = (args?.breakpoints ?? []).map((item) => Number(item.line)).filter((line) => Number.isFinite(line) && line > 0);
    this.breakpoints = [...new Set(lines)].sort((a, b) => a - b);
    this.log(`[debug] breakpoints: ${this.breakpoints.join(", ") || "none"}`);
    if (this.configured) {
      await this.connection?.debugSetBreakpoints(this.breakpoints);
    }
  }
  async onConfigurationDone() {
    if (!this.launchRequested) {
      throw new Error("\u8C03\u8BD5\u4F1A\u8BDD\u5C1A\u672A launch");
    }
    this.configured = true;
    await this.connection?.debugRun(this.scriptName, this.scriptText, this.breakpoints);
  }
  async onVariables(args, seq, command) {
    const reference = Number(args?.variablesReference ?? 0);
    if (!reference) {
      this.respond(seq, command, { variables: [] });
      return;
    }
    const deviceRef = reference - 1;
    const items = await this.connection?.debugVariables(deviceRef) ?? [];
    this.respond(seq, command, {
      variables: items.map((item) => ({
        name: item.name,
        value: item.value,
        variablesReference: typeof item.ref === "number" ? item.ref + 1 : 0
      }))
    });
  }
  async onEvaluate(args, seq, command) {
    const expression = String(args?.expression ?? "");
    const frameId = Number(args?.frameId ?? 0);
    if (!expression) {
      this.respond(seq, command, { result: "", variablesReference: 0 });
      return;
    }
    if (!this.connection) {
      throw new Error("\u8BBE\u5907\u672A\u8FDE\u63A5");
    }
    if (this.running) {
      throw new Error("\u811A\u672C\u6B63\u5728\u8FD0\u884C\uFF1ARhino \u8C03\u8BD5\u5668\u53EA\u80FD\u5728\u6682\u505C\u65F6\u6C42\u503C");
    }
    const deviceFrame = frameId > 0 ? frameId - 1 : 0;
    const result = await this.connection.debugEvaluate(expression, deviceFrame);
    this.log(`[debug] eval ${expression} = ${result}`);
    this.respond(seq, command, { result, variablesReference: 0 });
  }
  async onResume(action, seq) {
    if (!this.connection) {
      throw new Error("\u8BBE\u5907\u672A\u8FDE\u63A5");
    }
    await this.connection.debugResume(action);
    this.running = true;
    this.respond(seq, action, { allThreadsContinued: true });
  }
  // ---- 设备事件 → DAP 事件 ----
  onDeviceEvent(event) {
    switch (event.event) {
      case "started":
        this.frames = [];
        this.running = true;
        this.event("process", { name: this.scriptName, startMethod: "launch" });
        this.event("thread", { reason: "started", threadId: 1 });
        this.event("output", {
          category: "console",
          output: `AI.js Pro: \u5F00\u59CB\u8C03\u8BD5 ${this.scriptName}
`
        });
        return;
      case "stopped":
        this.running = false;
        this.frames = event.frames ?? [];
        this.log(`[debug] stopped line=${event.line} reason=${event.reason} frames=${this.frames.length}`);
        this.event("output", {
          category: "console",
          output: `AI.js Pro: ${event.reason === "step" ? "\u5355\u6B65" : "\u65AD\u70B9"}\u505C\u6B62\u4E8E\u7B2C ${event.line} \u884C
`
        });
        this.event("stopped", {
          reason: event.reason === "step" ? "step" : "breakpoint",
          threadId: 1,
          allThreadsStopped: true,
          preserveFocusHint: false
        });
        return;
      case "output":
        this.event("output", { category: "stdout", output: `${event.message ?? ""}
` });
        return;
      case "terminated":
        this.running = false;
        this.frames = [];
        this.log("[debug] terminated");
        this.event("terminated");
        return;
      default:
        this.log(`[debug] \u672A\u77E5\u8BBE\u5907\u4E8B\u4EF6\uFF1A${JSON.stringify(event)}`);
    }
  }
  stackFrames() {
    return this.frames.map((frame, index) => ({
      id: index + 1,
      name: frame.name && frame.name.length > 0 ? frame.name : "(anonymous)",
      line: frame.line,
      column: 1,
      source: this.sourceFor(frame.url)
    }));
  }
  sourceFor(url) {
    if (this.programPath && (url.includes(this.scriptName) || this.scriptName.includes(url))) {
      return { name: this.scriptName, path: this.programPath };
    }
    return { name: url || this.scriptName };
  }
  // ---- 发送封装 ----
  respond(seq, command, body, message) {
    const response = {
      type: "response",
      request_seq: seq,
      command,
      success: message === void 0
    };
    if (message !== void 0) {
      response.message = message;
    }
    if (body !== void 0) {
      response.body = body;
    }
    this.send(response);
  }
  event(event, body) {
    this.send({ type: "event", event, body });
  }
  send(message) {
    this.emitter.fire(message);
  }
};
function createDebugAdapterFactory(manager2, output) {
  return {
    createDebugAdapterDescriptor() {
      return new vscode3.DebugAdapterInlineImplementation(new AijsproDebugSession(manager2, output));
    }
  };
}
function createDebugConfigurationProvider() {
  return {
    provideDebugConfigurations() {
      return [
        {
          type: "aijspro",
          request: "launch",
          name: "AI.js Pro: \u8C03\u8BD5\u5F53\u524D\u6587\u4EF6\uFF08Rhino\uFF09",
          program: "${file}"
        }
      ];
    },
    resolveDebugConfiguration(_folder, config) {
      if (!config.type && !config.request && !config.name) {
        const editor = vscode3.window.activeTextEditor;
        if (!editor || editor.document.uri.scheme !== "file") {
          vscode3.window.showWarningMessage("AI.js Pro: \u8BF7\u5148\u6253\u5F00\u4E00\u4E2A .js \u6587\u4EF6\u518D\u8C03\u8BD5");
          return void 0;
        }
        config.type = "aijspro";
        config.request = "launch";
        config.name = "AI.js Pro: \u8C03\u8BD5\u5F53\u524D\u6587\u4EF6\uFF08Rhino\uFF09";
        config.program = editor.document.fileName;
      }
      if (!config.program) {
        config.program = "${file}";
      }
      if (!config.host) {
        const remembered = vscode3.workspace.getConfiguration("aijspro").get("devices", []);
        if (remembered.length === 1) {
          config.host = remembered[0];
        }
      }
      return config;
    }
  };
}

// src/filePolicy.ts
var path3 = __toESM(require("path"));
var TEXT_EXTENSIONS = /* @__PURE__ */ new Set([
  ".js",
  ".auto",
  ".mjs",
  ".cjs",
  ".jsx",
  ".ts",
  ".tsx",
  ".java",
  ".kt",
  ".kts",
  ".xml",
  ".json",
  ".jsonc",
  ".txt",
  ".log",
  ".md",
  ".markdown",
  ".yaml",
  ".yml",
  ".toml",
  ".ini",
  ".properties",
  ".cfg",
  ".conf",
  ".gradle",
  ".sh",
  ".py",
  ".csv",
  ".css",
  ".scss",
  ".html",
  ".htm",
  ".svg"
]);
var TEXT_NAMES = /* @__PURE__ */ new Set([
  ".gitignore",
  ".gitattributes",
  ".editorconfig",
  ".env",
  "dockerfile",
  "makefile",
  "license",
  "readme",
  "gradlew"
]);
var IMAGE_EXTENSIONS = /* @__PURE__ */ new Set([
  ".png",
  ".jpg",
  ".jpeg",
  ".webp",
  ".gif",
  ".bmp",
  ".ico"
]);
var SCRIPT_EXTENSIONS = /* @__PURE__ */ new Set([".js", ".auto"]);
function deviceFileKind(devicePath) {
  const name = path3.posix.basename(devicePath).toLowerCase();
  const extension = path3.posix.extname(name);
  if (TEXT_NAMES.has(name) || TEXT_EXTENSIONS.has(extension)) return "text";
  if (IMAGE_EXTENSIONS.has(extension)) return "image";
  return "download";
}
function isRunnableDeviceFile(devicePath) {
  return SCRIPT_EXTENSIONS.has(path3.posix.extname(devicePath).toLowerCase());
}

// src/manager.ts
var net = __toESM(require("net"));
var import_events2 = require("events");
var vscode4 = __toESM(require("vscode"));

// src/connection.ts
var zlib = __toESM(require("zlib"));
var import_events = require("events");

// src/protocol.ts
var crypto = __toESM(require("crypto"));
var TYPE_JSON = 1;
var TYPE_BYTES = 2;
var TYPE_GZIP_JSON = 3;
var TYPE_GZIP_BYTES = 4;
var MAX_FRAME_LENGTH = 256 * 1024 * 1024;
function encodeFrame(type, payload) {
  const frame = Buffer.allocUnsafe(8 + payload.length);
  frame.writeUInt32BE(payload.length, 0);
  frame.writeUInt32BE(type >>> 0, 4);
  payload.copy(frame, 8);
  return frame;
}
function encodeJsonFrame(value) {
  return encodeFrame(TYPE_JSON, Buffer.from(JSON.stringify(value), "utf8"));
}
function md5Hex(data) {
  return crypto.createHash("md5").update(data).digest("hex");
}
var FrameDecoder = class {
  buffer = Buffer.alloc(0);
  append(chunk) {
    this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk]);
    const frames = [];
    while (this.buffer.length >= 8) {
      const length = this.buffer.readUInt32BE(0);
      const type = this.buffer.readUInt32BE(4);
      if (length > MAX_FRAME_LENGTH) {
        throw new Error(`\u5E27\u957F\u5EA6\u975E\u6CD5\uFF1A${length}`);
      }
      if (this.buffer.length < 8 + length) {
        break;
      }
      frames.push({ type, payload: this.buffer.subarray(8, 8 + length) });
      this.buffer = this.buffer.subarray(8 + length);
    }
    return frames;
  }
};

// src/connection.ts
var DeviceConnection = class extends import_events.EventEmitter {
  constructor(socket, label, log, deviceAddress) {
    super();
    this.label = label;
    this.log = log;
    this.deviceAddress = deviceAddress;
    this.socket = socket;
    socket.setNoDelay(true);
    socket.on("data", (chunk) => this.onData(chunk));
    socket.on("close", () => this.onClosed());
    socket.on("error", (error) => {
      const code = error.code ? `${error.code} ` : "";
      this.log(`[\u8FDE\u63A5 ${this.label}] socket \u9519\u8BEF\uFF1A${code}${error.message}`);
    });
  }
  socket;
  hello = null;
  /** 是否已完成 hello 握手。 */
  attached = false;
  /** 最近一次 run / run_project 的 id，用于 stop。 */
  lastRunId;
  lastRunKind;
  decoder = new FrameDecoder();
  nextMessageId = 1;
  pending = /* @__PURE__ */ new Map();
  bufferedBytes = /* @__PURE__ */ new Map();
  byteWaiters = /* @__PURE__ */ new Map();
  helloAnswered = false;
  closed = false;
  /** 设备标识（hello.device_id 优先，握手前用连接标签）。 */
  get key() {
    return this.hello?.device_id || this.label;
  }
  get deviceName() {
    return this.hello?.device_name || this.label;
  }
  isClosed() {
    return this.closed;
  }
  close() {
    this.socket.destroy();
  }
  // ---- 命令 ----
  /** `command`：run / stop / stopAll / save / list_files / read_file / write_file … */
  async sendCommand(data, timeoutMs = 2e4) {
    const command = commandName(data);
    const id = this.nextMessageId++;
    const result = await this.request(id, encodeJsonFrame({ id, type: "command", data }), timeoutMs, command);
    return ensureResponseOk(result, command);
  }
  async run(name, id, script) {
    await this.sendCommand({ command: "run", id, name, script });
    this.lastRunId = id;
    this.lastRunKind = "script";
  }
  async stop(id) {
    const target = id ?? this.lastRunId;
    if (!target) {
      throw new Error("\u5F53\u524D\u8FDE\u63A5\u5C1A\u672A\u8FD0\u884C\u811A\u672C\uFF1B\u5982\u9700\u505C\u6B62\u8BBE\u5907\u4E0A\u7684\u5168\u90E8\u4EFB\u52A1\uFF0C\u8BF7\u4F7F\u7528\u201C\u505C\u6B62\u5168\u90E8\u201D");
    }
    const result = await this.sendCommand({ command: "stop", id: target });
    if (!id && this.lastRunKind === "project" && result?.stopped === void 0) {
      throw new Error("\u8BBE\u5907\u672A\u786E\u8BA4\u505C\u6B62\u8BE5\u5DE5\u7A0B\uFF0C\u8BF7\u5237\u65B0\u4EFB\u52A1\u72B6\u6001\u6216\u660E\u786E\u4F7F\u7528\u201C\u505C\u6B62\u5168\u90E8\u201D");
    }
    return { stopped: result?.stopped !== false, fallbackStopAll: false };
  }
  async stopAll() {
    const result = await this.sendCommand({ command: "stopAll" });
    return Number(result?.stoppedCount ?? 0);
  }
  async save(name, script) {
    await this.sendCommand({ command: "save", id: name, name, script });
  }
  async writeFile(path6, text) {
    await this.sendCommand({ command: "write_file", path: path6, script: text });
  }
  async listFiles(path6, recursive) {
    const result = await this.sendCommand(
      { command: "list_files", path: path6, recursive },
      recursive ? 6e4 : 2e4
    );
    return {
      files: Array.isArray(result?.files) ? result.files : [],
      dirs: Array.isArray(result?.dirs) ? result.dirs : [],
      truncated: Boolean(result?.truncated)
    };
  }
  /** 读取设备文件：内容走 bytes 帧，response 里带 md5 用于配对。 */
  async readFile(path6, timeoutMs = 3e4) {
    return (await this.readBytesVia({ command: "read_file", path: path6 }, timeoutMs)).bytes;
  }
  /** 布局分析：无障碍捕获当前窗口，返回 XML 文本（无障碍未开启时 reject）。 */
  async inspectLayout(timeoutMs = 2e4) {
    const { bytes } = await this.readBytesVia({ command: "inspect_layout" }, timeoutMs);
    return bytes.toString("utf8");
  }
  /** 图色助手：无障碍全屏截图（PNG + 原始像素尺寸，坐标为屏幕坐标）。 */
  async captureScreenshot(timeoutMs = 25e3) {
    const { result, bytes } = await this.readBytesVia({ command: "screenshot" }, timeoutMs);
    return {
      png: bytes,
      width: Number(result?.width ?? 0),
      height: Number(result?.height ?? 0)
    };
  }
  /** 发一条返回 bytes（配 md5）的命令：read_file / inspect_layout / screenshot 共用。 */
  async readBytesVia(data, timeoutMs) {
    const command = commandName(data);
    const id = this.nextMessageId++;
    const result = ensureResponseOk(
      await this.request(id, encodeJsonFrame({ id, type: "command", data }), timeoutMs, command),
      command
    );
    const md5 = String(result?.md5 ?? "");
    if (!md5) {
      throw new Error(`\u547D\u4EE4 ${command} \u7684\u54CD\u5E94\u7F3A\u5C11\u6587\u4EF6 md5`);
    }
    const buffered = this.bufferedBytes.get(md5);
    if (buffered) {
      this.bufferedBytes.delete(md5);
      return { result, bytes: buffered };
    }
    return { result, bytes: await this.waitForBytes(md5, 5e3, command) };
  }
  /**
   * `bytes_command`：先发 zip 字节帧再发带 md5 的 json。
   * 这是 AI.js Pro 设备端现有的配对顺序，保持帧格式不变。
   */
  async sendBytesCommand(command, name, archive, deletedFiles, timeoutMs = 18e4) {
    const id = this.nextMessageId++;
    const md5 = md5Hex(archive);
    const response = this.awaitResponse(id, timeoutMs, command);
    try {
      this.sendRaw(encodeFrame(TYPE_BYTES, archive));
      this.sendRaw(
        encodeJsonFrame({
          id,
          type: "bytes_command",
          md5,
          data: { command, id: name, name, deletedFiles, override: true }
        })
      );
    } catch (error) {
      this.cancelPending(id, error);
      return response;
    }
    const result = ensureResponseOk(await response, command);
    if (command === "run_project") {
      this.lastRunId = name;
      this.lastRunKind = "project";
    }
    return result;
  }
  // ---- 断点调试（Rhino） ----
  /** 启动调试会话：断点行号 1-based；事件通过 `debugEvent` 事件抛出。 */
  async debugRun(name, script, breakpoints) {
    await this.sendCommand({ command: "debug_run", name, script, breakpoints }, 2e4);
  }
  async debugSetBreakpoints(lines) {
    await this.sendCommand({ command: "debug_set_breakpoints", breakpoints: lines }, 2e4);
  }
  async debugResume(action) {
    await this.sendCommand({ command: "debug_resume", action }, 2e4);
  }
  async debugEvaluate(expr, frame) {
    const result = await this.sendCommand({ command: "debug_evaluate", expr, frame }, 2e4);
    return String(result?.result ?? "");
  }
  async debugVariables(ref) {
    const result = await this.sendCommand({ command: "debug_variables", ref }, 2e4);
    return Array.isArray(result?.variables) ? result.variables : [];
  }
  async debugTerminate() {
    try {
      await this.sendCommand({ command: "debug_terminate" }, 2e4);
    } catch {
    }
  }
  // ---- 内部 ----
  /** 发送一条请求并等待对应 response；断线时抛错，绝不能静默等满超时。 */
  request(id, frame, timeoutMs, command) {
    const response = this.awaitResponse(id, timeoutMs, command);
    try {
      this.sendRaw(frame);
    } catch (error) {
      this.cancelPending(id, error);
    }
    return response;
  }
  cancelPending(id, error) {
    const pending = this.pending.get(id);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending.delete(id);
    pending.reject(error);
  }
  sendRaw(frame) {
    if (this.closed || this.socket.destroyed) {
      throw new Error(`\u8FDE\u63A5\u5DF2\u65AD\u5F00\uFF0C\u547D\u4EE4\u672A\u53D1\u9001\uFF08${this.label}\uFF09`);
    }
    this.socket.write(frame);
  }
  onData(chunk) {
    let frames;
    try {
      frames = this.decoder.append(chunk);
    } catch (error) {
      this.log(`[\u8FDE\u63A5 ${this.label}] ${error.message}`);
      this.close();
      return;
    }
    for (const frame of frames) {
      try {
        this.dispatch(frame.type, frame.payload);
      } catch (error) {
        this.log(`[\u8FDE\u63A5 ${this.label}] \u5904\u7406\u5E27\u5931\u8D25\uFF08type=${frame.type}\uFF09\uFF1A${error.message}`);
        this.close();
        return;
      }
    }
  }
  dispatch(type, payload) {
    if (type === TYPE_BYTES || type === TYPE_GZIP_BYTES) {
      const data = type === TYPE_GZIP_BYTES ? zlib.gunzipSync(payload) : payload;
      this.onBytes(data);
      return;
    }
    if (type !== TYPE_JSON && type !== TYPE_GZIP_JSON) {
      this.log(`[\u8FDE\u63A5 ${this.label}] \u672A\u77E5\u5E27\u7C7B\u578B ${type}`);
      return;
    }
    const text = type === TYPE_GZIP_JSON ? zlib.gunzipSync(payload).toString("utf8") : payload.toString("utf8");
    let message;
    try {
      message = JSON.parse(text);
    } catch {
      this.log(`[\u8FDE\u63A5 ${this.label}] \u65E0\u6CD5\u89E3\u6790 JSON \u5E27`);
      return;
    }
    this.onMessage(message);
  }
  onMessage(message) {
    const type = typeof message?.type === "string" ? message.type : "";
    if (type === "hello") {
      this.onHello(message);
      return;
    }
    if (type === "log") {
      this.emit("log", String(message?.data?.log ?? ""));
      return;
    }
    if (type === "debug_event") {
      this.emit("debugEvent", message?.data ?? {});
      return;
    }
    if (type.startsWith("response_")) {
      const id = Number(type.slice("response_".length));
      const pending = this.pending.get(id);
      if (pending) {
        this.pending.delete(id);
        clearTimeout(pending.timer);
        pending.resolve(message?.data ?? {});
      }
      return;
    }
    this.log(`[\u8FDE\u63A5 ${this.label}] \u672A\u5904\u7406\u7684\u6D88\u606F\uFF1A${type}`);
  }
  onHello(message) {
    if (!this.helloAnswered) {
      this.helloAnswered = true;
      this.sendRaw(
        encodeJsonFrame({
          id: this.nextMessageId++,
          type: "hello",
          data: { client_version: 4, client_name: "AI.js Pro", extension_version: require_package().version }
        })
      );
    }
    this.socket.setTimeout(0);
    this.hello = message?.data ?? {};
    if (!this.attached) {
      this.attached = true;
      this.emit("connected");
    }
  }
  onBytes(payload) {
    const md5 = md5Hex(payload);
    const waiter = this.byteWaiters.get(md5);
    if (waiter) {
      this.byteWaiters.delete(md5);
      clearTimeout(waiter.timer);
      waiter.resolve(payload);
      return;
    }
    this.bufferedBytes.set(md5, payload);
    while (this.bufferedBytes.size > 8) {
      const oldest = this.bufferedBytes.keys().next().value;
      this.bufferedBytes.delete(oldest);
    }
  }
  waitForBytes(md5, timeoutMs, command) {
    if (this.closed || this.socket.destroyed) {
      return Promise.reject(new Error(`\u8FDE\u63A5\u5DF2\u65AD\u5F00\uFF08${command} \u7684\u6587\u4EF6\u5185\u5BB9\u672A\u5230\u8FBE\uFF09`));
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.byteWaiters.delete(md5);
        reject(new Error(`\u7B49\u5F85 ${command} \u6587\u4EF6\u5185\u5BB9\u8D85\u65F6\uFF08${formatTimeout(timeoutMs)}\uFF0Cbytes \u5E27\u672A\u5230\u8FBE\uFF09`));
      }, timeoutMs);
      this.byteWaiters.set(md5, { resolve, reject, timer, command });
    });
  }
  awaitResponse(id, timeoutMs, command) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`\u547D\u4EE4 ${command} \u54CD\u5E94\u8D85\u65F6\uFF08${formatTimeout(timeoutMs)}\uFF0Cid=${id}\uFF09`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer, command });
    });
  }
  onClosed() {
    if (this.closed) {
      return;
    }
    this.closed = true;
    for (const [, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(new Error(`\u8FDE\u63A5\u5DF2\u65AD\u5F00\uFF08\u547D\u4EE4 ${pending.command} \u672A\u5B8C\u6210\uFF09`));
    }
    this.pending.clear();
    for (const [, waiter] of this.byteWaiters) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error(`\u8FDE\u63A5\u5DF2\u65AD\u5F00\uFF08\u7B49\u5F85 ${waiter.command} \u7684\u6587\u4EF6\u5185\u5BB9\u65F6\u8FDE\u63A5\u5173\u95ED\uFF09`));
    }
    this.byteWaiters.clear();
    this.bufferedBytes.clear();
    this.emit("closed");
  }
};
function commandName(data) {
  const command = data?.command;
  return typeof command === "string" && command ? command : "command";
}
function ensureResponseOk(result, command) {
  if (result?.error !== void 0 && result?.error !== null && result?.error !== "") {
    throw new Error(`\u547D\u4EE4 ${command} \u5931\u8D25\uFF1A${String(result.error)}`);
  }
  return result;
}
function formatTimeout(timeoutMs) {
  return timeoutMs >= 1e3 ? `${Math.round(timeoutMs / 1e3)} \u79D2` : `${timeoutMs} \u6BEB\u79D2`;
}

// src/manager.ts
var HANDSHAKE_TIMEOUT_MS = 12e3;
var DEFAULT_LISTEN_PORT = 8327;
var DEFAULT_DEVICE_PORT = 9317;
var DeviceManager = class extends import_events2.EventEmitter {
  constructor(log) {
    super();
    this.log = log;
    for (const host of vscode4.workspace.getConfiguration("aijspro").get("ignoredDevices", [])) {
      this.ignoredHosts.add(deviceHost(host));
    }
  }
  server;
  connections = /* @__PURE__ */ new Set();
  retryTimer;
  autoConnectEnabled = false;
  disposed = false;
  ignoredHosts = /* @__PURE__ */ new Set();
  deviceIntents = /* @__PURE__ */ new Map();
  configurationWrites = Promise.resolve();
  /** 最近一次完成握手的连接 = “当前设备”。 */
  active;
  selectedDeviceKey;
  get all() {
    return [...this.connections];
  }
  get activeConnection() {
    if (this.selectedDeviceKey) {
      return this.all.find((conn) => conn.key === this.selectedDeviceKey && conn.attached && !conn.isClosed());
    }
    return this.active && !this.active.isClosed() ? this.active : this.connections.values().next().value;
  }
  /** 启动 8327 监听（可在 `aijspro.listenPort` 自定义；手机记住电脑后会主动回连到这里）。 */
  startListening() {
    const rawPort = vscode4.workspace.getConfiguration("aijspro").get("listenPort", DEFAULT_LISTEN_PORT);
    let port;
    try {
      port = parsePort(String(rawPort), "aijspro.listenPort");
    } catch (error) {
      this.log(`\u76D1\u542C\u7AEF\u53E3\u914D\u7F6E\u65E0\u6548\uFF1A${error.message}\uFF1B\u5DF2\u8DF3\u8FC7\u76D1\u542C\uFF0C\u624B\u673A\u5C06\u65E0\u6CD5\u4E3B\u52A8\u56DE\u8FDE\uFF08\u4ECD\u53EF\u624B\u52A8\u8FDE\u63A5\uFF09`);
    }
    if (port !== void 0) {
      const listenPort = port;
      const server = net.createServer((socket) => {
        const host = shortHost(socket.remoteAddress);
        const origin = formatHostPort(host, socket.remotePort ?? 0);
        if (this.ignoredHosts.has(deviceHost(host))) {
          this.log(`\u5FFD\u7565\u5DF2\u5220\u9664\u8BBE\u5907\u7684\u56DE\u8FDE\uFF1A${origin}`);
          socket.destroy();
          return;
        }
        this.log(`\u624B\u673A\u56DE\u8FDE\uFF1A${origin}`);
        this.adopt(socket, origin, true);
      });
      server.on("error", (error) => {
        const code = error.code ? `${error.code} ` : "";
        this.log(`\u76D1\u542C ${listenPort} \u5931\u8D25\uFF1A${code}${error.message}\uFF08\u624B\u673A\u4E3B\u52A8\u56DE\u8FDE\u4F1A\u53D7\u5F71\u54CD\uFF0C\u4ECD\u53EF\u624B\u52A8\u8FDE\u63A5\uFF09`);
      });
      try {
        server.listen(listenPort, () => {
          const kind = listenPort === DEFAULT_LISTEN_PORT ? "\u9ED8\u8BA4" : "\u81EA\u5B9A\u4E49";
          this.log(`\u6B63\u5728\u76D1\u542C ${listenPort}\uFF08${kind}\u624B\u673A\u56DE\u8FDE\u7AEF\u53E3\uFF0C\u53EF\u5728 aijspro.listenPort \u4FEE\u6539\uFF09\u2026`);
        });
        this.server = server;
      } catch (error) {
        this.log(`\u76D1\u542C ${listenPort} \u5931\u8D25\uFF1A${error.message}\uFF08\u624B\u673A\u4E3B\u52A8\u56DE\u8FDE\u4F1A\u53D7\u5F71\u54CD\uFF0C\u4ECD\u53EF\u624B\u52A8\u8FDE\u63A5\uFF09`);
        try {
          server.close();
        } catch {
        }
      }
    }
    this.autoConnectEnabled = vscode4.workspace.getConfiguration("aijspro").get("autoConnect", true);
    if (this.autoConnectEnabled) {
      this.retrySavedDevices();
      this.scheduleRetry(5e3);
    }
  }
  /**
   * 主动连接设备（host 可带端口，默认 9317；IPv6 写 `[地址]:端口`）。
   * 只发起 TCP 连接，hello 握手完成由 `connected` 事件通知；调用方无需等待握手。
   */
  async connectTo(host) {
    const target = splitHost(host, DEFAULT_DEVICE_PORT);
    const ip = normalizeHost(target.ip);
    const intent = (this.deviceIntents.get(ip) ?? 0) + 1;
    this.deviceIntents.set(ip, intent);
    await this.writeConfiguration(async () => {
      if (this.deviceIntents.get(ip) !== intent) return;
      await this.updateAddressList("ignoredDevices", (list) => list.filter((item) => deviceHost(item) !== ip));
    });
    if (this.deviceIntents.get(ip) !== intent) return;
    this.ignoredHosts.delete(ip);
    this.dialDevice(host);
  }
  dialDevice(host) {
    if (this.disposed) return;
    const { ip, port } = splitHost(host, DEFAULT_DEVICE_PORT);
    const address = formatHostPort(ip, port);
    if (this.ignoredHosts.has(normalizeHost(ip))) return;
    if (this.isConnectedTo(ip, port)) {
      this.log(`${address} \u5DF2\u7ECF\u8FDE\u63A5\uFF0C\u5FFD\u7565\u91CD\u590D\u8FDE\u63A5`);
      return;
    }
    this.log(`\u6B63\u5728\u8FDE\u63A5\u624B\u673A ${address}\u2026`);
    const socket = net.createConnection({ host: ip, port });
    this.adopt(socket, address, false, host.trim());
  }
  /** 删除记住的地址、关闭该手机的连接并阻止自动拨号/回连；手动连接可重新启用。 */
  async forgetDevices(hosts) {
    const ips = new Set(hosts.map(deviceHost));
    if (ips.size === 0) return;
    for (const ip of ips) {
      this.ignoredHosts.add(ip);
      this.deviceIntents.set(ip, (this.deviceIntents.get(ip) ?? 0) + 1);
    }
    for (const conn of [...this.connections]) {
      let address = conn.socket.remoteAddress;
      if (!address) {
        try {
          address = splitHost(conn.label, DEFAULT_DEVICE_PORT).ip;
        } catch {
          address = void 0;
        }
      }
      if (address && ips.has(deviceHost(address))) conn.close();
    }
    await this.writeConfiguration(async () => {
      await this.updateAddressList("devices", (list) => list.filter((host) => !ips.has(deviceHost(host))));
      await this.updateAddressList("ignoredDevices", (list) => [.../* @__PURE__ */ new Set([...list.map(deviceHost), ...ips])]);
    });
    this.scheduleRetry(5e3);
    this.log(`\u5DF2\u5220\u9664\u8BBE\u5907\uFF1A${[...ips].join("\u3001")}\uFF0C\u5DF2\u505C\u6B62\u8FD9\u4E9B\u5730\u5740\u7684\u81EA\u52A8\u8FDE\u63A5`);
    this.emit("changed");
  }
  disconnectActive() {
    const conn = this.activeConnection;
    conn?.close();
  }
  /** 「断开所有连接(Disconnect)」：手机侧会按记忆重新回连。 */
  disconnectAll() {
    for (const conn of [...this.connections]) {
      conn.close();
    }
  }
  /** 「选择设备(Select Device)」：把某个已连接设备设为当前设备。 */
  setActive(conn) {
    this.selectedDeviceKey = conn.key;
    this.active = conn;
    this.emit("changed");
  }
  /** 「重新扫描设备(Scan Devices)」：对所有已记住的设备再拨一次（已连的跳过）。 */
  scanNow() {
    let tried = 0;
    for (const host of this.retryDevices()) {
      try {
        const { ip, port } = splitHost(host, DEFAULT_DEVICE_PORT);
        if (this.isConnectedTo(ip, port)) {
          continue;
        }
        this.dialDevice(host);
        tried++;
      } catch (error) {
        this.log(`\u91CD\u8FDE ${host} \u5931\u8D25\uFF1A${error.message}`);
      }
    }
    return tried;
  }
  dispose() {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = void 0;
    this.disposed = true;
    this.autoConnectEnabled = false;
    this.server?.close();
    for (const conn of this.connections) {
      conn.close();
    }
    this.connections.clear();
  }
  /** 按 label/设备名/路径挑一个连接（多设备时用 QuickPick）。 */
  async pickConnection() {
    const list = this.all.filter((conn) => conn.attached);
    if (list.length === 0) {
      return void 0;
    }
    if (this.selectedDeviceKey) return list.find((conn) => conn.key === this.selectedDeviceKey);
    if (list.length === 1) {
      return list[0];
    }
    const picked = await vscode4.window.showQuickPick(
      list.map((conn) => ({
        label: conn.deviceName,
        description: conn.label,
        conn
      })),
      { placeHolder: "\u9009\u62E9\u8BBE\u5907" }
    );
    return picked?.conn;
  }
  adopt(socket, label, inbound, dialedHost) {
    const startedAt = Date.now();
    socket.setTimeout(HANDSHAKE_TIMEOUT_MS, () => {
      this.log(`[\u8FDE\u63A5 ${label}] \u7B49\u5F85\u624B\u673A hello \u8D85\u65F6\uFF08${Math.round(HANDSHAKE_TIMEOUT_MS / 1e3)} \u79D2\uFF09\uFF0C\u4E3B\u52A8\u65AD\u5F00`);
      socket.destroy();
    });
    const conn = new DeviceConnection(
      socket,
      label,
      this.log,
      inbound ? formatHostPort(shortHost(socket.remoteAddress), DEFAULT_DEVICE_PORT) : dialedHost
    );
    this.connections.add(conn);
    conn.once("connected", () => {
      const host = inbound ? shortHost(socket.remoteAddress) : dialedHost;
      if (host && this.ignoredHosts.has(deviceHost(host))) {
        conn.close();
        return;
      }
      if (!this.selectedDeviceKey || conn.key === this.selectedDeviceKey) this.active = conn;
      if (host) {
        this.rememberDevice(host);
      }
      this.log(
        `\u5DF2\u8FDE\u63A5\uFF1A${conn.deviceName}\uFF08${conn.label}\uFF0Capp ${conn.hello?.app_version ?? "?"}\uFF0C\u534F\u8BAE v${conn.hello?.server_version ?? "?"}\uFF0C\u63E1\u624B ${Date.now() - startedAt}ms\uFF09`
      );
      this.emit("changed");
      if (this.autoConnectEnabled) this.scheduleRetry(3e4);
    });
    conn.once("closed", () => {
      this.connections.delete(conn);
      if (this.active === conn) {
        this.active = void 0;
      }
      if (conn.attached) {
        this.log(`\u5DF2\u65AD\u5F00\uFF1A${conn.deviceName}\uFF08${conn.label}\uFF0C\u8FDE\u63A5 ${formatDuration(Date.now() - startedAt)}\uFF09`);
      } else {
        this.log(`\u8FDE\u63A5\u5931\u8D25\uFF1A${conn.label}\uFF08\u672A\u5B8C\u6210 hello \u63E1\u624B\uFF0C${formatDuration(Date.now() - startedAt)}\uFF09`);
      }
      this.emit("changed");
      if (this.autoConnectEnabled) this.scheduleRetry(3e3);
    });
    conn.on("log", (line) => this.emit("deviceLog", conn, line));
  }
  /** An adb forward is identified by host AND port; multiple phones share 127.0.0.1. */
  connectionAt(address) {
    try {
      const { ip, port } = splitHost(address, DEFAULT_DEVICE_PORT);
      return this.all.find((conn) => conn.attached && !conn.isClosed() && this.matchesEndpoint(conn, ip, port));
    } catch {
      return void 0;
    }
  }
  matchesEndpoint(conn, ip, port) {
    try {
      const target = splitHost(conn.deviceAddress || conn.label, DEFAULT_DEVICE_PORT);
      return normalizeHost(target.ip) === normalizeHost(ip) && target.port === port;
    } catch {
      return false;
    }
  }
  isConnectedTo(ip, port) {
    for (const conn of this.connections) {
      if (!conn.isClosed() && this.matchesEndpoint(conn, ip, port)) {
        return true;
      }
    }
    return false;
  }
  matchesHost(conn, ip) {
    const target = normalizeHost(ip);
    if (normalizeHost(shortHost(conn.socket.remoteAddress)) === target) return true;
    try {
      return normalizeHost(splitHost(conn.label, DEFAULT_DEVICE_PORT).ip) === target;
    } catch {
      return false;
    }
  }
  retrySavedDevices() {
    for (const host of this.retryDevices()) {
      try {
        const { ip, port } = splitHost(host, DEFAULT_DEVICE_PORT);
        if (!this.isConnectedTo(ip, port)) {
          this.dialDevice(host);
        }
      } catch (error) {
        this.log(`\u81EA\u52A8\u8FDE\u63A5 ${host} \u5931\u8D25\uFF1A${error.message}`);
      }
    }
  }
  /** Offline devices get a short retry; once connected, reduce background traffic. */
  scheduleRetry(delayMs) {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = void 0;
    if (!this.autoConnectEnabled || this.disposed || this.retryDevices().length === 0) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = void 0;
      if (!this.autoConnectEnabled) return;
      this.retrySavedDevices();
      const allRememberedConnected = this.retryDevices().every((host) => {
        try {
          return !!this.connectionAt(host);
        } catch {
          return false;
        }
      });
      this.scheduleRetry(allRememberedConnected ? 3e4 : 5e3);
    }, delayMs);
  }
  rememberDevice(host) {
    let target;
    try {
      target = splitHost(host, DEFAULT_DEVICE_PORT);
    } catch {
      return;
    }
    const ip = normalizeHost(target.ip);
    if (!ip || isLoopbackHost(ip)) {
      return;
    }
    const savedHost = formatHostPort(ip, target.port);
    void this.writeConfiguration(async () => {
      if (this.ignoredHosts.has(ip)) return;
      const list = savedDevices();
      if (list.some((item) => isSameAddress(item, ip, target.port))) return;
      await vscode4.workspace.getConfiguration("aijspro").update("devices", [...list, savedHost], vscode4.ConfigurationTarget.Global);
      this.log(`\u5DF2\u8BB0\u4F4F\u8BBE\u5907\u5730\u5740\uFF1A${savedHost}`);
    }).catch((error) => this.log(`\u4FDD\u5B58\u8BBE\u5907\u5730\u5740\u5931\u8D25\uFF1A${error.message}`));
  }
  retryDevices() {
    return savedDevices().filter((host) => {
      if (this.ignoredHosts.has(deviceHost(host))) return false;
      try {
        splitHost(host, DEFAULT_DEVICE_PORT);
        return true;
      } catch {
        return false;
      }
    });
  }
  writeConfiguration(action) {
    const operation = this.configurationWrites.then(action);
    this.configurationWrites = operation.catch(() => {
    });
    return operation;
  }
  /** 同时处理用户/工作区覆盖，避免删除后被另一层旧设置再次带回来。 */
  async updateAddressList(key, transform) {
    const configuration = vscode4.workspace.getConfiguration("aijspro");
    const values = configuration.inspect(key);
    const scopes = [
      [vscode4.ConfigurationTarget.Global, values?.globalValue ?? []],
      [vscode4.ConfigurationTarget.Workspace, values?.workspaceValue],
      [vscode4.ConfigurationTarget.WorkspaceFolder, values?.workspaceFolderValue]
    ];
    for (const [target, value] of scopes) {
      if (!value) continue;
      const next = transform(value);
      if (JSON.stringify(next) !== JSON.stringify(value)) await configuration.update(key, next, target);
    }
  }
};
function deviceHost(host) {
  try {
    return normalizeHost(splitHost(host, DEFAULT_DEVICE_PORT).ip);
  } catch {
    return normalizeHost(host);
  }
}
function savedDevices() {
  return vscode4.workspace.getConfiguration("aijspro").get("devices", []);
}
function splitHost(host, defaultPort) {
  const trimmed = (host ?? "").trim();
  if (!trimmed) {
    throw new Error("\u8BBE\u5907\u5730\u5740\u4E3A\u7A7A");
  }
  let ip = trimmed;
  let rawPort;
  if (trimmed.startsWith("[")) {
    const end = trimmed.indexOf("]");
    if (end < 0) {
      throw new Error(`\u8BBE\u5907\u5730\u5740\u683C\u5F0F\u65E0\u6548\uFF1A${trimmed}\uFF08IPv6 \u8BF7\u5199\u6210 [\u5730\u5740]:\u7AEF\u53E3\uFF09`);
    }
    ip = trimmed.slice(1, end).trim();
    const rest = trimmed.slice(end + 1);
    if (rest) {
      if (!rest.startsWith(":")) {
        throw new Error(`\u8BBE\u5907\u5730\u5740\u683C\u5F0F\u65E0\u6548\uFF1A${trimmed}\uFF08IPv6 \u8BF7\u5199\u6210 [\u5730\u5740]:\u7AEF\u53E3\uFF09`);
      }
      rawPort = rest.slice(1);
    }
    if (net.isIP(ip) !== 6) {
      throw new Error(`IPv6 \u5730\u5740\u65E0\u6548\uFF1A${ip}`);
    }
  } else if (trimmed.includes(":")) {
    const colonCount = trimmed.split(":").length - 1;
    if (colonCount > 1) {
      if (net.isIP(trimmed) !== 6) {
        throw new Error(`\u8BBE\u5907\u5730\u5740\u683C\u5F0F\u65E0\u6548\uFF1A${trimmed}\uFF08\u5E26\u7AEF\u53E3\u7684 IPv6 \u8BF7\u5199\u6210 [\u5730\u5740]:\u7AEF\u53E3\uFF09`);
      }
      ip = trimmed;
    } else {
      const index = trimmed.lastIndexOf(":");
      ip = trimmed.slice(0, index).trim();
      rawPort = trimmed.slice(index + 1);
    }
  }
  if (!ip || /\s/.test(ip)) {
    throw new Error(`\u8BBE\u5907\u5730\u5740\u683C\u5F0F\u65E0\u6548\uFF1A${trimmed}`);
  }
  const port = rawPort === void 0 ? defaultPort : parsePort(rawPort, trimmed);
  return { ip, port };
}
function parsePort(value, source) {
  if (!/^\d+$/.test(value)) {
    throw new Error(`\u7AEF\u53E3\u65E0\u6548\uFF1A${value}\uFF08${source}\uFF0C\u7AEF\u53E3\u5E94\u4E3A 1-65535\uFF09`);
  }
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`\u7AEF\u53E3\u65E0\u6548\uFF1A${value}\uFF08${source}\uFF0C\u7AEF\u53E3\u5E94\u4E3A 1-65535\uFF09`);
  }
  return port;
}
function formatHostPort(host, port) {
  const clean = stripBrackets(host);
  return `${clean.includes(":") ? `[${clean}]` : clean}:${port}`;
}
function shortHost(address) {
  if (!address) {
    return "";
  }
  const clean = stripBrackets(address.trim());
  const mapped = /^::ffff:/i.test(clean) ? clean.slice(7) : clean;
  return mapped.toLowerCase();
}
function normalizeHost(host) {
  return shortHost(host);
}
function stripBrackets(host) {
  return host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host;
}
function isLoopbackHost(host) {
  const value = normalizeHost(host);
  return value === "localhost" || value === "::1" || value === "0:0:0:0:0:0:0:1" || value.startsWith("127.");
}
function isSameAddress(host, ip, port) {
  try {
    const target = splitHost(host, DEFAULT_DEVICE_PORT);
    return normalizeHost(target.ip) === normalizeHost(ip) && target.port === port;
  } catch {
    return false;
  }
}
function formatDuration(milliseconds) {
  const seconds = Math.max(0, Math.round(milliseconds / 1e3));
  return seconds < 60 ? `${seconds} \u79D2` : `${Math.floor(seconds / 60)} \u5206 ${seconds % 60} \u79D2`;
}

// src/adb.ts
var import_child_process = require("child_process");
var fs3 = __toESM(require("fs"));
var path4 = __toESM(require("path"));
function parseAdbDevices(output) {
  return output.split(/\r?\n/).flatMap((line) => {
    const match = /^(\S+)\s+(device|unauthorized|offline)\b(.*)$/.exec(line.trim());
    if (!match) return [];
    const model = /\bmodel:(\S+)/.exec(match[3])?.[1];
    return [{ serial: match[1], state: match[2], name: model?.replace(/_/g, " ") || match[1] }];
  });
}
var UsbDevices = class {
  constructor(configuredPath = () => "") {
    this.configuredPath = configuredPath;
  }
  forwards = /* @__PURE__ */ new Map();
  disposed = false;
  async adbPath() {
    const configured = this.configuredPath().trim();
    if (configured) return configured;
    const name = process.platform === "win32" ? "adb.exe" : "adb";
    const roots = [
      process.env.ANDROID_SDK_ROOT,
      process.env.ANDROID_HOME,
      process.env.LOCALAPPDATA && path4.join(process.env.LOCALAPPDATA, "Android", "Sdk"),
      process.platform === "win32" ? "C:/Android" : void 0
    ];
    for (const root of roots) {
      if (!root) continue;
      const candidate = path4.join(root, "platform-tools", name);
      if (fs3.existsSync(candidate)) return candidate;
    }
    return name;
  }
  run(adb, args) {
    return new Promise((resolve, reject) => {
      (0, import_child_process.execFile)(adb, args, { timeout: 8e3, windowsHide: true, maxBuffer: 1024 * 1024 }, (error, stdout, stderr) => {
        if (error) {
          const code = error.code;
          reject(new Error(code === "ENOENT" ? "\u627E\u4E0D\u5230 adb\uFF0C\u8BF7\u5728 aijspro.adbPath \u8BBE\u7F6E Android platform-tools \u7684 adb \u8DEF\u5F84" : String(stderr).trim() || error.message));
        } else resolve(String(stdout).trim());
      });
    });
  }
  async list() {
    return parseAdbDevices(await this.run(await this.adbPath(), ["devices", "-l"]));
  }
  addressFor(serial) {
    const value = this.forwards.get(serial);
    return value ? `127.0.0.1:${value.port}` : void 0;
  }
  async connectAddress(serial) {
    if (this.disposed) throw new Error("USB \u8FDE\u63A5\u7BA1\u7406\u5668\u5DF2\u5173\u95ED");
    const existing = this.forwards.get(serial);
    if (existing) return `127.0.0.1:${existing.port}`;
    const adb = await this.adbPath();
    const value = await this.run(adb, ["-s", serial, "forward", "tcp:0", "tcp:9317"]);
    const port = Number(value);
    if (!/^\d+$/.test(value) || port < 1 || port > 65535) throw new Error(`USB \u7AEF\u53E3\u8F6C\u53D1\u5931\u8D25\uFF1A${value}`);
    if (this.disposed) {
      await this.run(adb, ["-s", serial, "forward", "--remove", `tcp:${port}`]);
      throw new Error("USB \u8FDE\u63A5\u5DF2\u53D6\u6D88");
    }
    this.forwards.set(serial, { adb, port });
    return `127.0.0.1:${port}`;
  }
  async dispose() {
    this.disposed = true;
    const entries = [...this.forwards];
    this.forwards.clear();
    await Promise.all(entries.map(([serial, { adb, port }]) => this.run(adb, ["-s", serial, "forward", "--remove", `tcp:${port}`]).catch(() => {
    })));
  }
};

// src/devicePicker.ts
var vscode5 = __toESM(require("vscode"));
async function selectDevice(manager2, usb, connectNew) {
  for (; ; ) {
    const rows = [];
    const online = manager2.all.filter((conn) => conn.attached && !conn.isClosed());
    for (const conn of online) rows.push({
      label: `$(device-mobile) ${conn.deviceName}`,
      description: manager2.activeConnection === conn ? "\u5DF2\u8FDE\u63A5 \xB7 \u5F53\u524D\u8BBE\u5907" : "\u5DF2\u8FDE\u63A5",
      detail: conn.deviceAddress || conn.label,
      conn
    });
    const known = savedDevices().filter((host) => !manager2.connectionAt(host));
    for (const host of known) rows.push({ label: `$(history) ${host}`, description: "\u5DF2\u8BB0\u4F4F \xB7 \u70B9\u51FB\u8FDE\u63A5", host });
    const items = usb.list().then((devices) => {
      for (const device of devices) {
        const address = usb.addressFor(device.serial);
        if (address && manager2.connectionAt(address)) continue;
        rows.push({
          label: `$(plug) ${device.name}`,
          description: device.state === "device" ? "USB \u624B\u673A" : device.state === "unauthorized" ? "USB \xB7 \u7B49\u5F85\u624B\u673A\u6388\u6743" : "USB \xB7 \u79BB\u7EBF",
          detail: device.serial,
          usb: device
        });
      }
      return rows;
    }, (error) => {
      rows.push({ label: "$(info) USB \u626B\u63CF\u4E0D\u53EF\u7528", description: "\u914D\u7F6E adb \u8DEF\u5F84", detail: error.message, action: "adb" });
      return rows;
    }).then((list) => [
      ...list,
      { label: "$(add) \u8F93\u5165\u624B\u673A\u5730\u5740\u8FDE\u63A5\u2026", action: "connect" },
      { label: "$(refresh) \u5237\u65B0\u8BBE\u5907\u5217\u8868", action: "refresh" }
    ]);
    const picked = await vscode5.window.showQuickPick(items, {
      title: "\u9009\u62E9 AI.js Pro \u8BBE\u5907",
      placeHolder: "\u9009\u62E9\u5DF2\u8FDE\u63A5\u3001USB \u6216\u5DF2\u8BB0\u4F4F\u7684\u624B\u673A\uFF1BEsc \u53D6\u6D88",
      matchOnDescription: true,
      matchOnDetail: true
    });
    if (!picked) return;
    if (picked.action === "refresh") continue;
    if (picked.action === "adb") {
      await vscode5.commands.executeCommand("workbench.action.openSettings", "aijspro.adbPath");
      return;
    }
    if (picked.action === "connect") {
      await connectNew();
      return;
    }
    if (picked.usb && picked.usb.state !== "device") {
      await vscode5.window.showWarningMessage(picked.usb.state === "unauthorized" ? "\u8BF7\u5728\u624B\u673A\u4E0A\u5141\u8BB8 USB \u8C03\u8BD5\u6388\u6743\uFF0C\u518D\u5237\u65B0\u8BBE\u5907\u5217\u8868\u3002" : "USB \u8BBE\u5907\u79BB\u7EBF\uFF0C\u8BF7\u91CD\u65B0\u8FDE\u63A5\u6570\u636E\u7EBF\u540E\u5237\u65B0\u3002");
      continue;
    }
    try {
      let conn = picked.conn;
      if (!conn?.attached || conn.isClosed()) {
        conn = await vscode5.window.withProgress({
          location: vscode5.ProgressLocation.Notification,
          title: `AI.js Pro: \u6B63\u5728\u8FDE\u63A5 ${picked.usb?.name || picked.host || picked.label}\u2026`
        }, async () => {
          const address = picked.usb ? await usb.connectAddress(picked.usb.serial) : picked.host || picked.conn?.deviceAddress;
          if (!address) throw new Error("\u8BBE\u5907\u5DF2\u65AD\u5F00\uFF0C\u8BF7\u5237\u65B0\u5217\u8868\u540E\u91CD\u9009");
          await manager2.connectTo(address);
          return waitForConnection(manager2, address);
        });
      }
      manager2.setActive(conn);
      vscode5.window.setStatusBarMessage(`AI.js Pro: \u5F53\u524D\u8BBE\u5907 ${conn.deviceName}`, 4e3);
      return;
    } catch (error) {
      await vscode5.window.showErrorMessage(`AI.js Pro: ${error.message}\u3002\u8BF7\u786E\u8BA4\u624B\u673A\u5DF2\u5F00\u542F\u201C\u7535\u8111\u8FDE\u63A5 \u2192 \u670D\u52A1\u5668\u6A21\u5F0F\u201D\u3002`);
    }
  }
}
function waitForConnection(manager2, address) {
  const ready = manager2.connectionAt(address);
  if (ready) return Promise.resolve(ready);
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timer);
      manager2.off("changed", check);
    };
    const check = () => {
      const conn = manager2.connectionAt(address);
      if (conn) {
        cleanup();
        resolve(conn);
      }
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`\u8FDE\u63A5 ${address} \u8D85\u65F6`));
    }, 12e3);
    manager2.on("changed", check);
    check();
  });
}

// src/tree.ts
var vscode6 = __toESM(require("vscode"));
function baseName(p) {
  const index = p.lastIndexOf("/");
  return index >= 0 ? p.slice(index + 1) : p;
}
var DeviceFileProvider = class {
  constructor(manager2) {
    this.manager = manager2;
    manager2.on("changed", () => this.refresh());
  }
  emitter = new vscode6.EventEmitter();
  onDidChangeTreeData = this.emitter.event;
  cache = /* @__PURE__ */ new Map();
  refresh() {
    this.cache.clear();
    this.emitter.fire(void 0);
  }
  getTreeItem(node) {
    switch (node.kind) {
      case "device": {
        const item = new vscode6.TreeItem(node.conn.deviceName, vscode6.TreeItemCollapsibleState.Expanded);
        item.description = node.conn.label;
        item.iconPath = new vscode6.ThemeIcon("device-mobile");
        return item;
      }
      case "dir": {
        const item = new vscode6.TreeItem(baseName(node.path) || node.path, vscode6.TreeItemCollapsibleState.Collapsed);
        item.contextValue = "aijspro.dir";
        item.iconPath = vscode6.ThemeIcon.Folder;
        return item;
      }
      case "file": {
        const item = new vscode6.TreeItem(baseName(node.path), vscode6.TreeItemCollapsibleState.None);
        item.description = formatSize(node.size);
        item.contextValue = isRunnableDeviceFile(node.path) ? "aijspro.script" : "aijspro.file";
        item.iconPath = new vscode6.ThemeIcon(
          isRunnableDeviceFile(node.path) ? "file-code" : deviceFileKind(node.path) === "image" ? "file-media" : "file"
        );
        item.command = {
          command: "aijspro.openDeviceFile",
          title: "\u6253\u5F00",
          arguments: [node]
        };
        return item;
      }
      default: {
        const item = new vscode6.TreeItem(node.label, vscode6.TreeItemCollapsibleState.None);
        item.iconPath = new vscode6.ThemeIcon("info");
        return item;
      }
    }
  }
  async getChildren(node) {
    if (!node) {
      const connections = this.manager.all;
      if (connections.length === 0) {
        return [{ kind: "info", label: "\u672A\u8FDE\u63A5\uFF1A\u6253\u5F00\u624B\u673A\u4FA7\u300C\u7535\u8111\u8FDE\u63A5 \u2192 \u670D\u52A1\u5668\u6A21\u5F0F\u300D\uFF0C\u518D\u6267\u884C\u300CAI.js Pro: \u8FDE\u63A5\u5230\u65B0\u8BBE\u5907\u300D" }];
      }
      return connections.map((conn) => ({ kind: "device", conn }));
    }
    if (node.kind === "device") {
      return this.list(node.conn, "");
    }
    if (node.kind === "dir") {
      return this.list(node.conn, node.path);
    }
    return [];
  }
  async list(conn, path6) {
    const cacheKey = `${conn.key}|${path6}`;
    let listing = this.cache.get(cacheKey);
    if (!listing) {
      try {
        listing = await conn.listFiles(path6, false);
      } catch (error) {
        return [{ kind: "info", label: `\u8BFB\u53D6\u76EE\u5F55\u5931\u8D25\uFF1A${error.message}` }];
      }
      this.cache.set(cacheKey, listing);
    }
    const dirs = listing.dirs.map((dir) => ({ kind: "dir", conn, path: dir }));
    const files = listing.files.map(
      (file) => ({
        kind: "file",
        conn,
        path: file.path,
        size: file.size
      })
    );
    const nodes = [...dirs, ...files];
    if (listing.truncated) {
      nodes.push({ kind: "info", label: "\uFF08\u6761\u76EE\u8FC7\u591A\uFF0C\u5DF2\u622A\u65AD\uFF09" });
    }
    return nodes;
  }
};
function formatSize(size) {
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

// src/zip.ts
var zlib2 = __toESM(require("zlib"));
var crcTable;
function crc32(buf) {
  if (!crcTable) {
    crcTable = [];
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) {
        c = c & 1 ? 3988292384 ^ c >>> 1 : c >>> 1;
      }
      crcTable[n] = c >>> 0;
    }
  }
  let crc = 4294967295;
  for (let i = 0; i < buf.length; i++) {
    crc = crcTable[(crc ^ buf[i]) & 255] ^ crc >>> 8;
  }
  return (crc ^ 4294967295) >>> 0;
}
function dosDateTime(date) {
  const time = (date.getHours() & 31) << 11 | (date.getMinutes() & 63) << 5 | date.getSeconds() / 2 & 31;
  const day = (date.getFullYear() - 1980 & 127) << 9 | (date.getMonth() + 1 & 15) << 5 | date.getDate() & 31;
  return { time, date: day };
}
function createZip(entries) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.path.replace(/\\/g, "/"), "utf8");
    const raw = entry.data;
    const deflated = zlib2.deflateRawSync(raw);
    const useDeflate = deflated.length < raw.length;
    const compressed = useDeflate ? deflated : raw;
    const method = useDeflate ? 8 : 0;
    const { time, date } = dosDateTime(entry.mtime ?? /* @__PURE__ */ new Date());
    const crc = crc32(raw);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(67324752, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(2048, 6);
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    localParts.push(local, name, compressed);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(33639248, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(2048, 8);
    central.writeUInt16LE(method, 10);
    central.writeUInt16LE(time, 12);
    central.writeUInt16LE(date, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(compressed.length, 20);
    central.writeUInt32LE(raw.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, name);
    offset += local.length + name.length + compressed.length;
  }
  const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(101010256, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralSize, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);
  return Buffer.concat([...localParts, ...centralParts, eocd]);
}

// src/extension.ts
var PUSH_MANIFEST_PREFIX = "aijspro.pushManifest";
var channel;
var manager;
var usbDevices;
var treeProvider;
var statusBar;
function activate(context) {
  channel = vscode7.window.createOutputChannel("AI.js Pro", { log: true });
  channel.appendLine("AI.js Pro \u6269\u5C55\u5DF2\u542F\u52A8");
  e2eLog("[ext] activated");
  manager = new DeviceManager((message) => channel.appendLine(message));
  usbDevices = new UsbDevices(() => vscode7.workspace.getConfiguration("aijspro").get("adbPath", ""));
  treeProvider = new DeviceFileProvider(manager);
  trackScriptEditors(context);
  const colorAssistant = new ColorAssistant(context, manager, (message) => {
    channel.appendLine(message);
    e2eLog(message);
  });
  statusBar = vscode7.window.createStatusBarItem(vscode7.StatusBarAlignment.Left, 90);
  statusBar.command = "aijspro.selectDevice";
  statusBar.show();
  let connectionLogShown = false;
  const updateStatus = () => {
    const conn = manager.activeConnection;
    if (conn?.attached) {
      statusBar.text = `$(radio-tower) AI.js Pro ${conn.deviceName}`;
      statusBar.tooltip = `${conn.deviceName} \xB7 ${conn.deviceAddress || conn.label}
\u70B9\u51FB\u5207\u6362\u8BBE\u5907`;
      statusBar.backgroundColor = void 0;
    } else {
      statusBar.text = "$(debug-disconnect) AI.js Pro: \u672A\u8FDE\u63A5";
      statusBar.tooltip = "\u70B9\u51FB\u9009\u62E9\u8BBE\u5907\uFF08\u652F\u6301 USB \u548C\u5C40\u57DF\u7F51\uFF09\uFF1B\u624B\u673A\u4FA7\u9700\u5F00\u542F\u201C\u7535\u8111\u8FDE\u63A5 \u2192 \u670D\u52A1\u5668\u6A21\u5F0F\u201D";
      statusBar.backgroundColor = void 0;
    }
  };
  manager.on("changed", () => {
    updateStatus();
    treeProvider.refresh();
    if (!connectionLogShown && manager.activeConnection?.attached && vscode7.workspace.getConfiguration("aijspro").get("showLogsOnConnect", true)) {
      connectionLogShown = true;
      channel.show(true);
    }
  });
  manager.on("deviceLog", (conn, line) => {
    const clean = line.replace(/\x1e/g, "").replace(/\r\n/g, "\n");
    for (const entry of clean.split("\n")) {
      if (entry.length > 0) channel.appendLine(`[${conn.deviceName}] ${entry}`);
    }
  });
  updateStatus();
  context.subscriptions.push(
    channel,
    statusBar,
    vscode7.window.registerTreeDataProvider("aijsproFiles", treeProvider),
    vscode7.commands.registerCommand("aijspro.help", () => helpCommand(context)),
    vscode7.commands.registerCommand("aijspro.connect", () => connectCommand()),
    vscode7.commands.registerCommand("aijspro.selectDevice", () => selectDeviceCommand()),
    vscode7.commands.registerCommand("aijspro.scanDevices", () => scanDevicesCommand()),
    vscode7.commands.registerCommand("aijspro.forgetDevices", () => forgetDevicesCommand()),
    vscode7.commands.registerCommand("aijspro.run", () => runActiveFileCommand(context)),
    vscode7.commands.registerCommand("aijspro.runFile", () => runFilePickerCommand(context)),
    vscode7.commands.registerCommand("aijspro.runProject", () => runProjectCommand(context)),
    vscode7.commands.registerCommand("aijspro.rerun", () => rerunCommand(context)),
    vscode7.commands.registerCommand("aijspro.stop", () => stopCurrentCommand()),
    vscode7.commands.registerCommand("aijspro.stopAll", () => stopAllCommand()),
    vscode7.commands.registerCommand("aijspro.inspectLayout", () => inspectLayoutCommand()),
    vscode7.commands.registerCommand("aijspro.pickColor", () => colorAssistant.open()),
    vscode7.commands.registerCommand("aijspro.browseFiles", () => vscode7.commands.executeCommand("aijsproFiles.focus")),
    vscode7.commands.registerCommand("aijspro.saveFile", () => pushFileCommand()),
    vscode7.commands.registerCommand("aijspro.pushFile", () => pushFileCommand()),
    vscode7.commands.registerCommand("aijspro.saveProject", () => saveProjectCommand()),
    vscode7.commands.registerCommand("aijspro.newRhinoProject", () => createDeviceProjectCommand("rhino")),
    vscode7.commands.registerCommand("aijspro.newQuickJsProject", () => createDeviceProjectCommand("quickjs")),
    vscode7.commands.registerCommand("aijspro.newRhinoProjectInWorkspace", () => createWorkspaceProjectCommand("rhino")),
    vscode7.commands.registerCommand("aijspro.newQuickJsProjectInWorkspace", () => createWorkspaceProjectCommand("quickjs")),
    vscode7.commands.registerCommand("aijspro.addDebugConfig", () => addDebugConfigCommand()),
    vscode7.commands.registerCommand("aijspro.debug", () => debugFileCommand()),
    vscode7.debug.registerDebugAdapterDescriptorFactory(
      "aijspro",
      createDebugAdapterFactory(manager, channel)
    ),
    vscode7.debug.registerDebugConfigurationProvider("aijspro", createDebugConfigurationProvider()),
    vscode7.commands.registerCommand("aijspro.disconnect", () => manager.disconnectAll()),
    vscode7.commands.registerCommand("aijspro.showLogs", () => channel.show(true)),
    vscode7.commands.registerCommand("aijspro.refreshFiles", () => treeProvider.refresh()),
    vscode7.commands.registerCommand("aijspro.togglePushOnSave", () => togglePushOnSave()),
    vscode7.commands.registerCommand("aijspro.menu", () => menuCommand()),
    vscode7.commands.registerCommand("aijspro.openDeviceFile", (node) => openDeviceFile(node, context)),
    vscode7.commands.registerCommand("aijspro.runDeviceFile", (node) => runDeviceFile(node)),
    vscode7.commands.registerCommand("aijspro.downloadFile", (node) => downloadFile(node)),
    vscode7.workspace.onDidSaveTextDocument((document) => pushOnSave(document)),
    { dispose: () => manager.dispose() }
  );
  manager.startListening();
  const directHost = process.env.AIJSPRO_E2E_CONNECT;
  if (directHost) {
    channel.appendLine(`[e2e] \u73AF\u5883\u53D8\u91CF\u76F4\u8FDE ${directHost}`);
    void manager.connectTo(directHost).catch((error) => channel.appendLine(`\u8FDE\u63A5\u5931\u8D25\uFF1A${error.message}`));
  }
  const e2eDebug = process.env.AIJSPRO_E2E_DEBUG;
  if (e2eDebug) {
    void autoDebugForE2E(Number(e2eDebug));
  }
  if (process.env.AIJSPRO_E2E_COLOR) {
    void autoColorForE2E(colorAssistant);
  }
}
async function deactivate() {
  manager?.dispose();
  await usbDevices?.dispose();
}
async function connectCommand() {
  const remembered = savedDevices();
  const picked = await vscode7.window.showQuickPick(
    [
      ...remembered.map((host2) => ({ label: host2 })),
      { label: "$(edit) \u8F93\u5165\u5730\u5740\u2026" },
      { label: "$(trash) \u5220\u9664\u5DF2\u8BB0\u4F4F\u7684\u8BBE\u5907\u2026" }
    ],
    { placeHolder: "\u9009\u62E9\u8981\u8FDE\u63A5\u7684\u8BBE\u5907\uFF08\u624B\u673A\u4FA7\u9700\u6253\u5F00\u300C\u7535\u8111\u8FDE\u63A5 \u2192 \u670D\u52A1\u5668\u6A21\u5F0F\u300D\uFF09" }
  );
  if (!picked) {
    return;
  }
  let host = picked.label;
  if (host.startsWith("$(trash)")) {
    await forgetDevicesCommand();
    return;
  }
  if (host.startsWith("$(edit)")) {
    const input = await vscode7.window.showInputBox({
      prompt: "\u8F93\u5165\u624B\u673A\u5730\u5740\uFF08\u5982 192.168.31.59 \u6216 192.168.31.59:9317\uFF09"
    });
    if (!input) {
      return;
    }
    host = input.trim();
  }
  try {
    await manager.connectTo(host);
  } catch (error) {
    vscode7.window.showErrorMessage(`AI.js Pro: \u8FDE\u63A5\u5931\u8D25\uFF1A${error.message}`);
    return;
  }
  vscode7.window.setStatusBarMessage(`AI.js Pro: \u6B63\u5728\u8FDE\u63A5 ${host}\u2026`, 4e3);
}
async function forgetDevicesCommand() {
  const remembered = savedDevices();
  if (remembered.length === 0) {
    vscode7.window.showInformationMessage("AI.js Pro: \u6CA1\u6709\u5DF2\u8BB0\u4F4F\u7684\u8BBE\u5907");
    return;
  }
  const selected = await vscode7.window.showQuickPick(
    remembered.map((host) => ({ label: host })),
    {
      canPickMany: true,
      title: "\u5220\u9664\u5DF2\u8BB0\u4F4F\u7684\u8BBE\u5907",
      placeHolder: "\u52FE\u9009\u8981\u5220\u9664\u7684\u5730\u5740\u5E76\u6309\u56DE\u8F66\uFF1B\u4F1A\u65AD\u5F00\u8FDE\u63A5\u5E76\u505C\u6B62\u81EA\u52A8\u91CD\u8FDE\uFF0C\u624B\u52A8\u8FDE\u63A5\u53EF\u6062\u590D"
    }
  );
  if (!selected?.length) return;
  try {
    await manager.forgetDevices(selected.map((item) => item.label));
    vscode7.window.showInformationMessage(`AI.js Pro: \u5DF2\u5220\u9664 ${selected.length} \u4E2A\u8BBE\u5907\u5730\u5740\uFF0C\u5DF2\u505C\u6B62\u81EA\u52A8\u91CD\u8FDE`);
  } catch (error) {
    vscode7.window.showErrorMessage(`AI.js Pro: \u5F53\u524D\u8FDE\u63A5\u5DF2\u53D6\u6D88\uFF0C\u4F46\u5220\u9664\u8BBE\u5907\u8BBE\u7F6E\u5931\u8D25\uFF1A${error.message}`);
  }
}
async function withConnection(action) {
  const conn = await manager.pickConnection();
  if (!conn) {
    await promptConnect();
    return void 0;
  }
  try {
    return await action(conn);
  } catch (error) {
    vscode7.window.showErrorMessage(`AI.js Pro: ${error.message}`);
    return void 0;
  }
}
async function stopCurrentCommand() {
  await withConnection(async (conn) => {
    const result = await conn.stop();
    const message = result.fallbackStopAll ? "\u65E0\u6CD5\u53EF\u9760\u5B9A\u4F4D\u5F53\u524D\u811A\u672C\uFF0C\u5DF2\u53D1\u9001\u201C\u505C\u6B62\u6240\u6709\u811A\u672C\u201D" : "\u5DF2\u53D1\u9001\u505C\u6B62\u5F53\u524D\u811A\u672C";
    channel.appendLine(`[${conn.deviceName}] ${message}`);
    vscode7.window.setStatusBarMessage(`AI.js Pro: ${message}`, 5e3);
  });
}
async function stopAllCommand() {
  await withConnection(async (conn) => {
    const count = await conn.stopAll();
    const message = count > 0 ? `\u5DF2\u505C\u6B62 ${count} \u4E2A\u811A\u672C` : "\u5DF2\u53D1\u9001\u505C\u6B62\u6240\u6709\u811A\u672C";
    channel.appendLine(`[${conn.deviceName}] ${message}`);
    vscode7.window.setStatusBarMessage(`AI.js Pro: ${message}`, 5e3);
  });
}
async function withNodeConnection(node, action) {
  const conn = node?.conn;
  if (!conn?.attached || conn.isClosed()) {
    vscode7.window.showWarningMessage("AI.js Pro: \u6B64\u6587\u4EF6\u6240\u5C5E\u8BBE\u5907\u5DF2\u65AD\u5F00\uFF0C\u8BF7\u5237\u65B0\u8BBE\u5907\u6587\u4EF6\u6811");
    return void 0;
  }
  try {
    return await action(conn);
  } catch (error) {
    vscode7.window.showErrorMessage(`AI.js Pro: ${error.message}`);
    return void 0;
  }
}
async function promptConnect() {
  const choice = await vscode7.window.showInformationMessage(
    "AI.js Pro: \u8BF7\u5148\u9009\u62E9\u5E76\u8FDE\u63A5\u8981\u4F7F\u7528\u7684\u8BBE\u5907\u3002",
    "\u9009\u62E9\u8BBE\u5907\u2026",
    "\u8FDE\u63A5\u8BBE\u5907\u2026",
    "\u663E\u793A\u65E5\u5FD7"
  );
  if (choice === "\u9009\u62E9\u8BBE\u5907\u2026") {
    await vscode7.commands.executeCommand("aijspro.selectDevice");
  } else if (choice === "\u8FDE\u63A5\u8BBE\u5907\u2026") {
    await vscode7.commands.executeCommand("aijspro.connect");
  } else if (choice === "\u663E\u793A\u65E5\u5FD7") {
    channel.show(true);
  }
}
async function runActiveFileCommand(context) {
  const editor = pickScriptEditor();
  if (!editor) {
    vscode7.window.showWarningMessage("AI.js Pro: \u6CA1\u6709\u53EF\u8FD0\u884C\u7684\u811A\u672C\u6587\u4EF6\uFF08\u5148\u6253\u5F00\u6216\u70B9\u4E00\u4E0B .js \u6587\u4EF6\uFF09");
    return;
  }
  const active = vscode7.window.activeTextEditor;
  if (active && !isScriptEditor(active)) {
    vscode7.window.setStatusBarMessage("AI.js Pro: \u7126\u70B9\u4E0D\u5728\u7F16\u8F91\u5668\u4E0A\uFF0C\u5DF2\u6539\u7528\u6700\u8FD1\u7F16\u8F91\u7684\u811A\u672C", 4e3);
  }
  const document = editor.document;
  const name = path5.basename(document.isUntitled ? "untitled.js" : document.fileName);
  const id = relativeToWorkspace(document) ?? name;
  const script = document.getText();
  await withConnection(async (conn) => {
    await conn.run(name, id, script);
    await context.workspaceState.update(rerunKey(conn), { kind: "script", name, id, script });
    channel.appendLine(`[${conn.deviceName}] \u25B6 ${name}`);
    vscode7.window.setStatusBarMessage(`AI.js Pro: \u5DF2\u53D1\u9001 ${name}`, 3e3);
  });
}
async function runProjectCommand(context) {
  const folder = await pickWorkspaceFolder();
  if (!folder) {
    return;
  }
  const options = vscode7.workspace.getConfiguration("aijspro");
  const exclude = new Set(options.get("exclude", []));
  const entries = await collectProjectFiles(folder.uri.fsPath, exclude);
  if (entries.length === 0) {
    vscode7.window.showWarningMessage("AI.js Pro: \u5DE5\u7A0B\u76EE\u5F55\u91CC\u6CA1\u6709\u53EF\u63A8\u9001\u7684\u6587\u4EF6");
    return;
  }
  const zipInputs = [];
  for (const entry of entries) {
    zipInputs.push({
      path: entry.relative,
      data: await fs4.promises.readFile(entry.absolute)
    });
  }
  const archive = createZip(zipInputs);
  if (archive.length > 200 * 1024 * 1024) {
    vscode7.window.showErrorMessage(
      `AI.js Pro: \u5DE5\u7A0B\u6253\u5305\u540E ${(archive.length / 1024 / 1024).toFixed(0)} MB\uFF0C\u8D85\u8FC7\u534F\u8BAE\u4E0A\u9650\uFF08256MB\uFF09\uFF0C\u8BF7\u7528 aijspro.exclude \u6392\u9664\u5927\u6587\u4EF6`
    );
    return;
  }
  const projectName = path5.basename(folder.uri.fsPath);
  await withConnection(async (conn) => {
    const manifestKey = `${PUSH_MANIFEST_PREFIX}.${conn.key}.${folder.uri.fsPath}`;
    const previous = context.globalState.get(manifestKey, []);
    const current = new Set(entries.map((entry) => entry.relative));
    const deletedFiles = previous.filter((relative2) => !current.has(relative2));
    await conn.sendBytesCommand("run_project", projectName, archive, deletedFiles);
    await context.globalState.update(
      manifestKey,
      entries.map((entry) => entry.relative)
    );
    await context.workspaceState.update(rerunKey(conn), {
      kind: "project",
      name: projectName,
      folder: folder.uri.fsPath
    });
    channel.appendLine(
      `[${conn.deviceName}] \u25B6 \u5DE5\u7A0B ${projectName}\uFF08${entries.length} \u4E2A\u6587\u4EF6\uFF0C${(archive.length / 1024).toFixed(0)} KB\uFF0C\u5220\u9664 ${deletedFiles.length}\uFF09`
    );
    vscode7.window.setStatusBarMessage(`AI.js Pro: \u5DF2\u63A8\u9001\u5E76\u8FD0\u884C ${projectName}`, 4e3);
  });
}
async function pushFileCommand() {
  const editor = vscode7.window.activeTextEditor;
  if (!editor) {
    vscode7.window.showWarningMessage("AI.js Pro: \u6CA1\u6709\u6253\u5F00\u7684\u6587\u4EF6");
    return;
  }
  const devicePath = devicePathForDocument(editor.document);
  if (!devicePath) {
    vscode7.window.showWarningMessage("AI.js Pro: \u8BE5\u6587\u4EF6\u4E0D\u5728\u5DE5\u4F5C\u533A\u5185\uFF0C\u65E0\u6CD5\u786E\u5B9A\u63A8\u9001\u8DEF\u5F84");
    return;
  }
  await withConnection(async (conn) => {
    await conn.writeFile(devicePath, editor.document.getText());
    vscode7.window.setStatusBarMessage(`AI.js Pro: \u5DF2\u63A8\u9001 ${devicePath}`, 4e3);
  });
}
async function openDeviceFile(node, context) {
  const kind = deviceFileKind(node.path);
  if (kind === "download") {
    const choice = await vscode7.window.showInformationMessage(
      `AI.js Pro: ${path5.posix.basename(node.path)} \u4E0D\u80FD\u76F4\u63A5\u9884\u89C8\uFF0C\u53EF\u4E0B\u8F7D\u5230\u5DE5\u4F5C\u533A\u540E\u7528\u5408\u9002\u7684\u5E94\u7528\u6253\u5F00\u3002`,
      "\u4E0B\u8F7D\u5230\u5DE5\u4F5C\u533A"
    );
    if (choice === "\u4E0B\u8F7D\u5230\u5DE5\u4F5C\u533A") await downloadFile(node);
    return;
  }
  await withNodeConnection(node, async (conn) => {
    const data = await conn.readFile(node.path);
    if (kind === "image") {
      const previewDir = vscode7.Uri.joinPath(context.globalStorageUri, "device-previews");
      await vscode7.workspace.fs.createDirectory(previewDir);
      const hash = (0, import_crypto.createHash)("sha256").update(`${conn.key}|${node.path}`).digest("hex").slice(0, 16);
      const preview = vscode7.Uri.joinPath(previewDir, `${hash}-${sanitize(path5.posix.basename(node.path))}`);
      await vscode7.workspace.fs.writeFile(preview, data);
      await vscode7.commands.executeCommand("vscode.open", preview, { preview: true });
      return;
    }
    let content;
    try {
      if (data.includes(0)) throw new Error("binary");
      content = new import_util.TextDecoder("utf-8", { fatal: true }).decode(data);
    } catch {
      vscode7.window.showWarningMessage(`AI.js Pro: ${node.path} \u4E0D\u662F\u6709\u6548 UTF-8 \u6587\u672C\uFF0C\u8BF7\u7528\u201C\u4E0B\u8F7D\u5230\u5DE5\u4F5C\u533A\u201D\u4FDD\u5B58\u539F\u6587\u4EF6`);
      return;
    }
    const document = await vscode7.workspace.openTextDocument({
      content,
      language: languageOf(node.path)
    });
    await vscode7.window.showTextDocument(document, { preview: true });
  });
}
async function runDeviceFile(node) {
  if (!isRunnableDeviceFile(node.path)) {
    vscode7.window.showWarningMessage("AI.js Pro: \u53EA\u6709 .js \u548C .auto \u811A\u672C\u53EF\u4EE5\u76F4\u63A5\u8FD0\u884C");
    return;
  }
  await withNodeConnection(node, async (conn) => {
    const data = await conn.readFile(node.path);
    const name = path5.basename(node.path);
    const script = new import_util.TextDecoder("utf-8", { fatal: true }).decode(data);
    await conn.run(name, node.path, script);
    channel.appendLine(`[${conn.deviceName}] \u25B6 ${node.path}`);
    vscode7.window.setStatusBarMessage(`AI.js Pro: \u5DF2\u5728\u624B\u673A\u4E0A\u8FD0\u884C ${name}`, 4e3);
  });
}
async function downloadFile(node) {
  await withNodeConnection(node, async (conn) => {
    const data = await conn.readFile(node.path);
    const folder = await pickWorkspaceFolder() ?? void 0;
    let target;
    if (folder) {
      target = path5.join(
        folder.uri.fsPath,
        "device-export",
        sanitize(conn.deviceName),
        ...node.path.split("/")
      );
    } else {
      const picked = await vscode7.window.showSaveDialog({
        defaultUri: vscode7.Uri.file(data ? path5.basename(node.path) : node.path)
      });
      if (!picked) {
        return;
      }
      target = picked.fsPath;
    }
    await fs4.promises.mkdir(path5.dirname(target), { recursive: true });
    await fs4.promises.writeFile(target, data);
    const choice = await vscode7.window.showInformationMessage(
      `AI.js Pro: \u5DF2\u4E0B\u8F7D\u5230 ${target}`,
      "\u6253\u5F00\u6587\u4EF6"
    );
    if (choice === "\u6253\u5F00\u6587\u4EF6") {
      await vscode7.commands.executeCommand("vscode.open", vscode7.Uri.file(target));
    }
  });
}
async function menuCommand() {
  const actions = [
    { label: "\u4F7F\u7528\u6559\u7A0B (Help)", command: "aijspro.help" },
    { label: "\u8FDE\u63A5\u5230\u65B0\u8BBE\u5907 (Connect)", command: "aijspro.connect" },
    { label: "\u9009\u62E9\u8BBE\u5907 (Select Device)", command: "aijspro.selectDevice" },
    { label: "\u91CD\u65B0\u626B\u63CF\u8BBE\u5907 (Scan Devices)", command: "aijspro.scanDevices" },
    { label: "\u5220\u9664\u5DF2\u8BB0\u4F4F\u7684\u8BBE\u5907 (Delete Devices)", command: "aijspro.forgetDevices" },
    { label: "\u8FD0\u884C (Run)", command: "aijspro.run" },
    { label: "\u8FD0\u884C\u9879\u76EE (Run Project)", command: "aijspro.runProject" },
    { label: "\u8FD0\u884C\u5355\u6587\u4EF6 (Run File)", command: "aijspro.runFile" },
    { label: "\u505C\u6B62\u6240\u6709\u811A\u672C (Stop All)", command: "aijspro.stopAll" },
    { label: "\u505C\u6B62\u5F53\u524D\u811A\u672C (Stop)", command: "aijspro.stop" },
    { label: "\u5E03\u5C40\u5206\u6790 (Inspect Layout)", command: "aijspro.inspectLayout" },
    { label: "\u56FE\u8272\u52A9\u624B (Screenshot & Pick Color)", command: "aijspro.pickColor" },
    { label: "\u6D4F\u89C8\u8BBE\u5907\u6587\u4EF6 (Browse Device Files)", command: "aijspro.browseFiles" },
    { label: "\u4FDD\u5B58\u5F53\u524D\u6587\u4EF6 (Save)", command: "aijspro.saveFile" },
    { label: "\u65B0\u5EFA\u7B2C\u4E00\u4EE3 API \u9879\u76EE (Rhino)", command: "aijspro.newRhinoProject" },
    { label: "\u65B0\u5EFA\u7B2C\u4E8C\u4EE3 API \u9879\u76EE (QuickJS)", command: "aijspro.newQuickJsProject" },
    { label: "\u5728\u5DE5\u4F5C\u533A\u5EFA\u7ACB\u7B2C\u4E00\u4EE3 API \u9879\u76EE (Rhino)", command: "aijspro.newRhinoProjectInWorkspace" },
    { label: "\u5728\u5DE5\u4F5C\u533A\u5EFA\u7ACB\u7B2C\u4E8C\u4EE3 API \u9879\u76EE (QuickJS)", command: "aijspro.newQuickJsProjectInWorkspace" },
    { label: "\u4FDD\u5B58\u9879\u76EE (Save Project)", command: "aijspro.saveProject" },
    { label: "\u91CD\u65B0\u8FD0\u884C (Rerun)", command: "aijspro.rerun" },
    { label: "\u589E\u52A0\u8C03\u8BD5\u8BBE\u5907\u914D\u7F6E (Add Debug Config)", command: "aijspro.addDebugConfig" },
    { label: "\u8C03\u8BD5\u5F53\u524D\u6587\u4EF6 (Debug File)", command: "aijspro.debug" },
    { label: "\u65AD\u5F00\u6240\u6709\u8FDE\u63A5 (Disconnect)", command: "aijspro.disconnect" },
    { label: "\u5237\u65B0\u6587\u4EF6 (Refresh Directory)", command: "aijspro.refreshFiles" },
    { label: "\u663E\u793A\u65E5\u5FD7 (Show Logs)", command: "aijspro.showLogs" },
    { label: "\u5F00\u5173\u4FDD\u5B58\u65F6\u81EA\u52A8\u63A8\u9001", command: "aijspro.togglePushOnSave" }
  ];
  const picked = await vscode7.window.showQuickPick(actions, { placeHolder: "AI.js Pro \u529F\u80FD\u83DC\u5355" });
  if (picked) await vscode7.commands.executeCommand(picked.command);
}
async function togglePushOnSave() {
  const config = vscode7.workspace.getConfiguration("aijspro");
  const next = !config.get("pushOnSave", false);
  await config.update("pushOnSave", next, vscode7.ConfigurationTarget.Global);
  vscode7.window.setStatusBarMessage(
    next ? "AI.js Pro: \u4FDD\u5B58\u65F6\u81EA\u52A8\u63A8\u9001\u5DF2\u5F00\u542F" : "AI.js Pro: \u4FDD\u5B58\u65F6\u81EA\u52A8\u63A8\u9001\u5DF2\u5173\u95ED",
    3e3
  );
}
async function pushOnSave(document) {
  if (!vscode7.workspace.getConfiguration("aijspro").get("pushOnSave", false)) {
    return;
  }
  const conn = manager.activeConnection;
  if (!conn?.attached || document.uri.scheme !== "file") {
    return;
  }
  const devicePath = devicePathForDocument(document);
  if (!devicePath) {
    return;
  }
  try {
    await conn.writeFile(devicePath, document.getText());
    channel.appendLine(`[${conn.deviceName}] \u21E7 \u5DF2\u63A8\u9001 ${devicePath}`);
  } catch (error) {
    channel.appendLine(`\u63A8\u9001\u5931\u8D25\uFF1A${error.message}`);
  }
}
function devicePathForDocument(document) {
  const folder = vscode7.workspace.getWorkspaceFolder(document.uri);
  if (!folder) {
    return void 0;
  }
  const relative2 = path5.relative(folder.uri.fsPath, document.uri.fsPath);
  if (relative2.startsWith("..") || path5.isAbsolute(relative2)) {
    return void 0;
  }
  return `${path5.basename(folder.uri.fsPath)}/${relative2.split(path5.sep).join("/")}`;
}
function relativeToWorkspace(document) {
  const folder = vscode7.workspace.getWorkspaceFolder(document.uri);
  if (!folder) {
    return void 0;
  }
  return path5.relative(folder.uri.fsPath, document.uri.fsPath).split(path5.sep).join("/");
}
async function pickWorkspaceFolder() {
  const folders = vscode7.workspace.workspaceFolders ?? [];
  if (folders.length === 0) {
    const editor = vscode7.window.activeTextEditor;
    if (editor && editor.document.uri.scheme === "file") {
      return {
        uri: vscode7.Uri.file(path5.dirname(editor.document.fileName)),
        name: path5.basename(path5.dirname(editor.document.fileName)),
        index: 0
      };
    }
    vscode7.window.showWarningMessage("AI.js Pro: \u9700\u8981\u6253\u5F00\u4E00\u4E2A\u6587\u4EF6\u5939\u5DE5\u4F5C\u533A");
    return void 0;
  }
  if (folders.length === 1) {
    return folders[0];
  }
  const picked = await vscode7.window.showQuickPick(
    folders.map((folder) => ({ label: folder.name, folder })),
    { placeHolder: "\u9009\u62E9\u8981\u63A8\u9001\u7684\u5DE5\u7A0B" }
  );
  return picked?.folder;
}
async function collectProjectFiles(root, exclude) {
  const result = [];
  const walk = async (dir) => {
    const children = await fs4.promises.readdir(dir, { withFileTypes: true });
    for (const child of children) {
      if (exclude.has(child.name)) {
        continue;
      }
      const absolute = path5.join(dir, child.name);
      if (child.isDirectory()) {
        await walk(absolute);
      } else if (child.isFile()) {
        result.push({ relative: path5.relative(root, absolute).split(path5.sep).join("/"), absolute });
      }
    }
  };
  await walk(root);
  return result;
}
function languageOf(devicePath) {
  const extension = path5.extname(devicePath).toLowerCase();
  switch (extension) {
    case ".js":
      return "javascript";
    case ".json":
      return "json";
    case ".ts":
      return "typescript";
    case ".md":
      return "markdown";
    default:
      return "plaintext";
  }
}
function sanitize(name) {
  return name.replace(/[\\/:*?"<>|]/g, "_");
}
function rerunKey(conn) {
  return `aijspro.lastRun.${conn.key}`;
}
function hostOf(conn) {
  return conn.deviceAddress ?? conn.label;
}
async function helpCommand(context) {
  await vscode7.commands.executeCommand(
    "markdown.showPreview",
    vscode7.Uri.joinPath(context.extensionUri, "README.md")
  );
}
async function selectDeviceCommand() {
  await selectDevice(manager, usbDevices, connectCommand);
}
async function scanDevicesCommand() {
  const tried = manager.scanNow();
  channel.appendLine(`\u626B\u63CF\u8BBE\u5907\uFF1A\u5DF2\u53D1\u8D77 ${tried} \u4E2A\u5DF2\u8BB0\u4F4F\u5730\u5740\u7684\u8FDE\u63A5\uFF0C\u6B63\u5728\u8BFB\u53D6 USB \u624B\u673A\u5217\u8868`);
  await selectDevice(manager, usbDevices, connectCommand);
}
async function runFilePickerCommand(context) {
  const folder = await pickWorkspaceFolder();
  if (!folder) {
    return;
  }
  const exclude = new Set(vscode7.workspace.getConfiguration("aijspro").get("exclude", []));
  const entries = (await collectProjectFiles(folder.uri.fsPath, exclude)).filter(
    (entry) => entry.relative.endsWith(".js")
  );
  if (entries.length === 0) {
    vscode7.window.showWarningMessage("AI.js Pro: \u5DE5\u4F5C\u533A\u91CC\u6CA1\u6709 .js \u6587\u4EF6");
    return;
  }
  const picked = await vscode7.window.showQuickPick(
    entries.map((entry) => entry.relative),
    { placeHolder: "\u9009\u62E9\u8981\u8FD0\u884C\u7684\u811A\u672C" }
  );
  if (!picked) {
    return;
  }
  const absolute = path5.join(folder.uri.fsPath, ...picked.split("/"));
  let script;
  try {
    script = await fs4.promises.readFile(absolute, "utf8");
  } catch (error) {
    vscode7.window.showErrorMessage(`AI.js Pro: \u8BFB\u53D6 ${picked} \u5931\u8D25\uFF1A${error.message}`);
    return;
  }
  const name = path5.basename(picked);
  await withConnection(async (conn) => {
    await conn.run(name, picked, script);
    await context.workspaceState.update(rerunKey(conn), { kind: "script", name, id: picked, script });
    channel.appendLine(`[${conn.deviceName}] \u25B6 ${picked}`);
    vscode7.window.setStatusBarMessage(`AI.js Pro: \u5DF2\u53D1\u9001 ${name}`, 3e3);
  });
}
async function inspectLayoutCommand() {
  await withConnection(async (conn) => {
    const xml = await conn.inspectLayout();
    const document = await vscode7.workspace.openTextDocument({ content: xml, language: "xml" });
    await vscode7.window.showTextDocument(document, { preview: true });
    channel.appendLine(`[${conn.deviceName}] \u5E03\u5C40\u5206\u6790\uFF1A${xml.length} \u5B57\u7B26`);
  });
}
async function saveProjectCommand() {
  const folder = await pickWorkspaceFolder();
  if (!folder) {
    return;
  }
  const exclude = new Set(vscode7.workspace.getConfiguration("aijspro").get("exclude", []));
  const entries = await collectProjectFiles(folder.uri.fsPath, exclude);
  if (entries.length === 0) {
    vscode7.window.showWarningMessage("AI.js Pro: \u5DE5\u7A0B\u76EE\u5F55\u91CC\u6CA1\u6709\u53EF\u4FDD\u5B58\u7684\u6587\u4EF6");
    return;
  }
  const zipInputs = [];
  for (const entry of entries) {
    zipInputs.push({ path: entry.relative, data: await fs4.promises.readFile(entry.absolute) });
  }
  const archive = createZip(zipInputs);
  const projectName = path5.basename(folder.uri.fsPath);
  await withConnection(async (conn) => {
    await conn.sendBytesCommand("save_project", projectName, archive, []);
    channel.appendLine(`[${conn.deviceName}] \u21E7 \u5DE5\u7A0B ${projectName} \u5DF2\u4FDD\u5B58\u5230\u624B\u673A\uFF08${entries.length} \u4E2A\u6587\u4EF6\uFF09`);
    vscode7.window.setStatusBarMessage(`AI.js Pro: \u5DF2\u4FDD\u5B58\u5DE5\u7A0B ${projectName} \u5230\u624B\u673A`, 4e3);
  });
}
async function rerunCommand(context) {
  await withConnection(async (conn) => {
    const last = context.workspaceState.get(rerunKey(conn));
    if (!last) {
      vscode7.window.showInformationMessage("AI.js Pro: \u8FD8\u6CA1\u6709\u53EF\u91CD\u65B0\u8FD0\u884C\u7684\u4EFB\u52A1");
      return;
    }
    if (last.kind === "script") {
      await conn.run(last.name, last.id, last.script);
      channel.appendLine(`[${conn.deviceName}] \u21BB ${last.name}`);
      return;
    }
    const exclude = new Set(vscode7.workspace.getConfiguration("aijspro").get("exclude", []));
    const entries = await collectProjectFiles(last.folder, exclude);
    const zipInputs = [];
    for (const entry of entries) {
      zipInputs.push({ path: entry.relative, data: await fs4.promises.readFile(entry.absolute) });
    }
    await conn.sendBytesCommand("run_project", last.name, createZip(zipInputs), []);
    channel.appendLine(`[${conn.deviceName}] \u21BB \u5DE5\u7A0B ${last.name}`);
  });
}
var RHINO_TEMPLATE = '"ui";\n// \u7B2C\u4E00\u4EE3 API\uFF08Rhino \u5F15\u64CE\uFF09\nconsole.log("Hello AI.js Pro");\n';
var QUICKJS_TEMPLATE = '// @engine quickjs\n// \u7B2C\u4E8C\u4EE3 API\uFF08QuickJS \u5F15\u64CE\uFF09\nconsole.log("Hello AI.js Pro");\n';
function projectJson(name, engine) {
  return `${JSON.stringify(
    {
      name,
      versionName: "1.0.0",
      versionCode: 1,
      main: "main.js",
      engine
    },
    null,
    2
  )}
`;
}
function engineOf(kind) {
  return { template: kind === "rhino" ? RHINO_TEMPLATE : QUICKJS_TEMPLATE, engine: kind };
}
async function createDeviceProjectCommand(kind) {
  const title = kind === "rhino" ? "\u7B2C\u4E00\u4EE3API\u9879\u76EE(Rhino)" : "\u7B2C\u4E8C\u4EE3API\u9879\u76EE(QuickJS)";
  const name = await vscode7.window.showInputBox({
    prompt: `\u65B0\u5EFA${title}\uFF1A\u9879\u76EE\u540D\uFF08\u521B\u5EFA\u5728\u624B\u673A\u811A\u672C\u76EE\u5F55\u4E0B\uFF09`,
    value: kind === "rhino" ? "rhino-project" : "quickjs-project"
  });
  if (!name) {
    return;
  }
  const safe = sanitize(name.trim());
  if (!safe) {
    vscode7.window.showWarningMessage("AI.js Pro: \u9879\u76EE\u540D\u4E0D\u5408\u6CD5");
    return;
  }
  await withConnection(async (conn) => {
    await conn.writeFile(`${safe}/project.json`, projectJson(safe, kind));
    await conn.writeFile(`${safe}/main.js`, engineOf(kind).template);
    channel.appendLine(`[${conn.deviceName}] \uFF0B \u5DF2\u521B\u5EFA\u9879\u76EE ${safe}\uFF08${title}\uFF09`);
    const choice = await vscode7.window.showInformationMessage(
      `AI.js Pro: \u5DF2\u5728\u624B\u673A\u521B\u5EFA\u9879\u76EE ${safe}`,
      "\u5237\u65B0\u8BBE\u5907\u6587\u4EF6"
    );
    if (choice === "\u5237\u65B0\u8BBE\u5907\u6587\u4EF6") {
      await vscode7.commands.executeCommand("aijspro.refreshFiles");
    }
  });
}
async function createWorkspaceProjectCommand(kind) {
  const title = kind === "rhino" ? "\u7B2C\u4E00\u4EE3API\u9879\u76EE(Rhino)" : "\u7B2C\u4E8C\u4EE3API\u9879\u76EE(QuickJS)";
  const folder = await pickWorkspaceFolder();
  if (!folder) {
    return;
  }
  const name = await vscode7.window.showInputBox({
    prompt: `\u5728\u5DE5\u4F5C\u533A\u5EFA\u7ACB${title}\uFF1A\u76EE\u5F55\u540D`,
    value: kind === "rhino" ? "rhino-project" : "quickjs-project"
  });
  if (!name) {
    return;
  }
  const safe = sanitize(name.trim());
  if (!safe) {
    vscode7.window.showWarningMessage("AI.js Pro: \u76EE\u5F55\u540D\u4E0D\u5408\u6CD5");
    return;
  }
  const dir = path5.join(folder.uri.fsPath, safe);
  if (fs4.existsSync(dir)) {
    vscode7.window.showWarningMessage(`AI.js Pro: ${safe} \u5DF2\u5B58\u5728`);
    return;
  }
  await fs4.promises.mkdir(dir, { recursive: true });
  await fs4.promises.writeFile(path5.join(dir, "project.json"), projectJson(safe, kind), "utf8");
  const mainPath = path5.join(dir, "main.js");
  await fs4.promises.writeFile(mainPath, engineOf(kind).template, "utf8");
  await vscode7.window.showTextDocument(vscode7.Uri.file(mainPath));
  channel.appendLine(`\uFF0B \u5DF2\u5EFA\u7ACB ${title}\uFF1A${mainPath}`);
}
async function addDebugConfigCommand() {
  const conn = await manager.pickConnection();
  let host = conn ? hostOf(conn) : void 0;
  if (!host) {
    host = await vscode7.window.showInputBox({
      prompt: "\u8BBE\u5907\u5730\u5740\uFF08\u5199\u5165\u5DE5\u4F5C\u533A .vscode/settings.json \u7684 aijspro.devices\uFF09"
    });
    host = host?.trim();
  }
  if (!host) {
    return;
  }
  const folder = vscode7.workspace.workspaceFolders?.[0];
  if (!folder) {
    vscode7.window.showWarningMessage("AI.js Pro: \u9700\u8981\u6253\u5F00\u4E00\u4E2A\u6587\u4EF6\u5939\u5DE5\u4F5C\u533A");
    return;
  }
  const vscodeDir = path5.join(folder.uri.fsPath, ".vscode");
  const settingsPath = path5.join(vscodeDir, "settings.json");
  let json = {};
  try {
    json = JSON.parse(await fs4.promises.readFile(settingsPath, "utf8"));
  } catch {
    json = {};
  }
  const devices = Array.isArray(json["aijspro.devices"]) ? json["aijspro.devices"] : [];
  if (!devices.includes(host)) {
    devices.push(host);
  }
  json["aijspro.devices"] = devices;
  await fs4.promises.mkdir(vscodeDir, { recursive: true });
  await fs4.promises.writeFile(settingsPath, `${JSON.stringify(json, null, 2)}
`, "utf8");
  const launchPath = path5.join(vscodeDir, "launch.json");
  let launch = { version: "0.2.0", configurations: [] };
  try {
    launch = JSON.parse(await fs4.promises.readFile(launchPath, "utf8"));
  } catch {
    launch = { version: "0.2.0", configurations: [] };
  }
  if (!Array.isArray(launch.configurations)) {
    launch.configurations = [];
  }
  const hasAijspro = launch.configurations.some((item) => item?.type === "aijspro");
  if (!hasAijspro) {
    launch.configurations.push({
      type: "aijspro",
      request: "launch",
      name: "AI.js Pro: \u8C03\u8BD5\u5F53\u524D\u6587\u4EF6\uFF08Rhino\uFF09",
      program: "${file}",
      host
    });
    await fs4.promises.writeFile(launchPath, `${JSON.stringify(launch, null, 2)}
`, "utf8");
  }
  vscode7.window.setStatusBarMessage(`AI.js Pro: \u5DF2\u5199\u5165 ${host} \u4E0E\u8C03\u8BD5\u914D\u7F6E`, 4e3);
}
async function debugFileCommand() {
  const editor = vscode7.window.activeTextEditor;
  if (!editor || editor.document.uri.scheme !== "file") {
    vscode7.window.showWarningMessage("AI.js Pro: \u8BF7\u5148\u6253\u5F00\u8981\u8C03\u8BD5\u7684 .js \u6587\u4EF6");
    return;
  }
  const folder = vscode7.workspace.getWorkspaceFolder(editor.document.uri);
  const started = await vscode7.debug.startDebugging(folder, {
    type: "aijspro",
    request: "launch",
    name: "AI.js Pro: \u8C03\u8BD5\u5F53\u524D\u6587\u4EF6\uFF08Rhino\uFF09",
    program: editor.document.fileName
  });
  if (!started) {
    vscode7.window.showWarningMessage("AI.js Pro: \u8C03\u8BD5\u4F1A\u8BDD\u672A\u80FD\u542F\u52A8\uFF08\u8BE6\u89C1\u300CAI.js Pro\u300D\u8F93\u51FA\uFF09");
  }
}
function e2eLog(message) {
  const sink = process.env.AIJSPRO_E2E_DEBUG_LOG;
  if (!sink) {
    return;
  }
  try {
    fs4.appendFileSync(sink, `${message}
`, "utf8");
  } catch {
  }
}
async function autoDebugForE2E(line) {
  e2eLog("[e2e] auto debug start");
  const deadline = Date.now() + 15e3;
  while (Date.now() < deadline && !manager.activeConnection?.attached) {
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  const conn = manager.activeConnection;
  if (!conn?.attached) {
    channel.appendLine("[e2e] \u672A\u80FD\u8FDE\u4E0A\u8BBE\u5907\uFF0C\u81EA\u52A8\u8C03\u8BD5\u653E\u5F03");
    e2eLog("[e2e] no device connection");
    return;
  }
  const folder = vscode7.workspace.workspaceFolders?.[0];
  if (!folder) {
    channel.appendLine("[e2e] \u6CA1\u6709\u5DE5\u4F5C\u533A\uFF0C\u81EA\u52A8\u8C03\u8BD5\u653E\u5F03");
    e2eLog("[e2e] no workspace");
    return;
  }
  const target = await findFirstScript(folder.uri.fsPath, 4);
  if (!target) {
    channel.appendLine("[e2e] \u5DE5\u4F5C\u533A\u91CC\u6CA1\u6709 .js \u6587\u4EF6\uFF0C\u81EA\u52A8\u8C03\u8BD5\u653E\u5F03");
    e2eLog("[e2e] no js file");
    return;
  }
  const document = await vscode7.workspace.openTextDocument(target);
  const position = new vscode7.Position(Math.max(0, (Number.isFinite(line) ? line : 1) - 1), 0);
  vscode7.debug.addBreakpoints([
    new vscode7.SourceBreakpoint(new vscode7.Location(document.uri, position))
  ]);
  channel.appendLine(`[e2e] \u5DF2\u4E0B\u65AD\u70B9 ${target}:${line}\uFF0C\u542F\u52A8\u8C03\u8BD5`);
  e2eLog(`[e2e] breakpoint set ${target}:${line}`);
  const started = await vscode7.debug.startDebugging(folder, {
    type: "aijspro",
    request: "launch",
    name: "AI.js Pro E2E",
    program: target
  });
  e2eLog(`[e2e] startDebugging=${started}`);
}
async function autoColorForE2E(assistant) {
  e2eLog("[e2e] auto color start");
  const deadline = Date.now() + 15e3;
  while (Date.now() < deadline && !manager.activeConnection?.attached) {
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  const conn = manager.activeConnection;
  if (!conn?.attached) {
    e2eLog("[e2e] no device connection");
    return;
  }
  e2eLog("[e2e] opening color assistant");
  await assistant.open();
  e2eLog("[e2e] color assistant opened");
  const argb = process.env.AIJSPRO_E2E_COLOR_TEST;
  if (argb) {
    e2eLog("[e2e] running device test " + argb);
    await assistant.runDeviceTest({ kind: "findColor", argb, threshold: 4 });
    e2eLog("[e2e] device test done");
  }
  const multiArgb = process.env.AIJSPRO_E2E_COLOR_TEST_MULTI;
  if (multiArgb) {
    e2eLog("[e2e] running multi test " + multiArgb);
    await assistant.runDeviceTest({
      kind: "findMulti",
      first: { x: 0, y: 0, argb: multiArgb },
      others: [
        { dx: 1, dy: 0, argb: multiArgb },
        { dx: 0, dy: 1, argb: multiArgb }
      ],
      threshold: 4
    });
    e2eLog("[e2e] multi test done");
  }
}
async function findFirstScript(dir, depth) {
  if (depth < 0) {
    return void 0;
  }
  let children;
  try {
    children = await fs4.promises.readdir(dir, { withFileTypes: true });
  } catch {
    return void 0;
  }
  for (const child of children) {
    if (child.isFile() && child.name.endsWith(".js")) {
      return path5.join(dir, child.name);
    }
  }
  for (const child of children) {
    if (child.isDirectory() && child.name !== "node_modules" && !child.name.startsWith(".")) {
      const found = await findFirstScript(path5.join(dir, child.name), depth - 1);
      if (found) {
        return found;
      }
    }
  }
  return void 0;
}
// Annotate the CommonJS export names for ESM import in node:
0 && (module.exports = {
  activate,
  deactivate
});
