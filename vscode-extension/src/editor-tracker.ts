/**
 * 「脚本目标编辑器」跟踪。
 *
 * <p>VS Code 里除了普通文件，很多东西也是 TextDocument（输出面板、调试控制台、Git diff、
 * 扩展宿主日志……）。把它们的文本当脚本发到手机（或往里插代码）都是错的，用户真实踩过：
 * 焦点在「AI.js Pro」输出面板时点 ▶，扩展把日志文本当脚本运行；往输出面板插代码则什么都不会发生。
 *
 * <p>所以这里统一维护「最近一个真正是脚本的编辑器」：scheme 必须是 file/untitled。
 * 面板抢走焦点后（activeTextEditor 变 undefined）也能回退到它。
 */
import * as vscode from 'vscode';

let lastEditor: vscode.TextEditor | undefined;

/** 是不是可以当脚本用的编辑器（文件或未保存的新建文件）。 */
export function isScriptEditor(editor: vscode.TextEditor | undefined): boolean {
  const scheme = editor?.document.uri.scheme;
  return scheme === 'file' || scheme === 'untitled';
}

/** 在 activate() 里调用一次：启动跟踪。 */
export function trackScriptEditors(context: vscode.ExtensionContext): void {
  const active = vscode.window.activeTextEditor;
  if (active && isScriptEditor(active)) {
    lastEditor = active;
  }
  context.subscriptions.push(
    vscode.window.onDidChangeActiveTextEditor((editor) => {
      if (editor && isScriptEditor(editor) && !editor.document.isClosed) {
        lastEditor = editor;
      }
    }),
  );
}

/**
 * 当前该被当成「脚本目标」的编辑器：活动的优先；活动的是输出/调试面板等非脚本编辑器时，
 * 回退到最近活跃的脚本编辑器。
 */
export function pickScriptEditor(): vscode.TextEditor | undefined {
  const active = vscode.window.activeTextEditor;
  if (active && isScriptEditor(active) && !active.document.isClosed) {
    return active;
  }
  if (lastEditor && !lastEditor.document.isClosed) {
    return lastEditor;
  }
  return undefined;
}
