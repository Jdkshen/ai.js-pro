/**
 * 断点调试（Rhino）端到端验证：用扩展协议直连真机跑一次真实调试会话。
 *
 * 用法：node dist/debug-e2e.js <手机IP> [端口=9317]
 *   （也可先 adb forward tcp:19317 tcp:9317，然后 node dist/debug-e2e.js 127.0.0.1 19317）
 */
import * as net from 'net';

import { DeviceConnection } from '../src/connection';

const host = process.argv[2] ?? '127.0.0.1';
const port = Number(process.argv[3] ?? 9317);
const logs: string[] = [];

interface DebugEvent {
  event: string;
  reason?: string;
  line?: number;
  name?: string;
  url?: string;
  frames?: { index: number; name: string; line: number; url: string }[];
  message?: string;
}

const events: DebugEvent[] = [];
const waiters: (() => void)[] = [];

function assert(condition: unknown, message = 'assertion failed'): void {
  if (!condition) {
    throw new Error(message);
  }
}

async function waitFor(check: () => boolean, timeoutMs: number, what: string): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (check()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 150));
  }
  throw new Error(`等待超时：${what}`);
}

function findStopped(fromIndex: number, line?: number): number {
  for (let i = fromIndex; i < events.length; i++) {
    const event = events[i];
    if (event.event === 'stopped' && (line === undefined || event.line === line)) {
      return i;
    }
  }
  return -1;
}

function dumpEvents(): string {
  return events.map((event) => JSON.stringify(event)).join('\n  ');
}

/** 被测脚本：第 3 行函数内下断点，第 4 行算 sum，便于验证 eval / 变量。 */
const script = [
  'var a = 1;', // 1
  'function work() {', // 2
  '    var b = 2;', // 3
  '    var sum = a + b;', // 4  ← 断点
  "    console.log('DEBUG_SUM ' + sum);", // 5
  '    return sum;', // 6
  '}', // 7
  'work();', // 8
  "console.log('DEBUG_END');", // 9
].join('\n');

async function main(): Promise<void> {
  const socket = net.createConnection({ host, port });
  const conn = new DeviceConnection(socket, `${host}:${port}`, (message) => console.log('[conn]', message));
  conn.on('log', (line: string) => {
    logs.push(line);
    console.log('[device]', line.trim());
  });
  conn.on('debugEvent', (event: DebugEvent) => {
    events.push(event);
    console.log('[debug]', JSON.stringify(event));
    for (const wake of waiters.splice(0)) {
      wake();
    }
  });

  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('hello 超时')), 10000);
    conn.once('connected', () => {
      clearTimeout(timer);
      resolve();
    });
    conn.once('closed', () => reject(new Error('握手前连接被关闭')));
  });
  console.log('== 已连接 ==', conn.deviceName, JSON.stringify(conn.hello));

  console.log('\n== 1) debug_run（断点在第 4 行）==');
  await conn.debugRun('probe-debug.js', script, [4]);
  await waitFor(() => events.some((event) => event.event === 'stopped'), 15000, '首次停止');
  const first = events.find((event) => event.event === 'stopped') as DebugEvent;
  console.log(`首次停止：line=${first.line} reason=${first.reason} frames=${first.frames?.length ?? 0}`);

  console.log('\n== 2) 继续到第 4 行断点 ==');
  let stoppedIndex = events.findIndex((event) => event.event === 'stopped');
  while (events[stoppedIndex].line !== 4) {
    console.log(`  当前停在 line=${events[stoppedIndex].line}，continue …`);
    const cursor = events.length;
    await conn.debugResume('continue');
    await waitFor(() => events.length > cursor, 15000, '下一次 debug 事件');
    stoppedIndex = findStopped(cursor, 4);
    if (stoppedIndex < 0) {
      const last = events[events.length - 1];
      if (last.event === 'terminated') {
        throw new Error('脚本在到达第 4 行断点前结束：\n  ' + dumpEvents());
      }
      stoppedIndex = events.findLastIndex((event) => event.event === 'stopped');
      if (stoppedIndex < 0) {
        throw new Error('未收到停止事件：\n  ' + dumpEvents());
      }
    }
  }
  const stopped = events[stoppedIndex];
  console.log(`命中：line=${stopped.line} reason=${stopped.reason} name=${stopped.name}`);
  console.log('调用栈：');
  for (const frame of stopped.frames ?? []) {
    console.log(`   #${frame.index} ${frame.name} @${frame.line} (${frame.url})`);
  }
  assert(stopped.line === 4, `期望停在第 4 行，实际 ${stopped.line}`);
  assert((stopped.frames?.length ?? 0) >= 2, '调用栈帧数应 ≥ 2（work + 全局）');

  console.log('\n== 3) debug_evaluate ==');
  const sum = await conn.debugEvaluate('a + b', 0);
  console.log(`a + b = ${sum}`);
  assert(sum.includes('3'), `eval 期望 3，实际 ${sum}`);
  const localB = await conn.debugEvaluate('b', 0);
  console.log(`b = ${localB}`);
  assert(localB.includes('2'), `eval b 期望 2，实际 ${localB}`);

  console.log('\n== 4) debug_variables（作用域对象）==');
  const vars = await conn.debugVariables(0);
  console.log('变量：', vars.map((item) => `${item.name}=${item.value}`).join(', '));
  const b = vars.find((item) => item.name === 'b');
  assert(b, '作用域里应能看到局部变量 b');

  console.log('\n== 5) 单步（next）==');
  const beforeStep = events.length;
  await conn.debugResume('next');
  await waitFor(() => events.length > beforeStep && events[events.length - 1].event === 'stopped', 15000, '单步停止');
  const afterStep = events[events.length - 1];
  console.log(`next 后停在 line=${afterStep.line} reason=${afterStep.reason}`);
  assert(afterStep.line === 5, `单步期望到第 5 行，实际 ${afterStep.line}`);

  console.log('\n== 6) 改断点 + 继续到结束 ==');
  await conn.debugSetBreakpoints([]);
  const beforeRun = events.length;
  await conn.debugResume('continue');
  await waitFor(
    () => logs.some((line) => line.includes('DEBUG_SUM')) && logs.some((line) => line.includes('DEBUG_END')),
    15000,
    '脚本日志',
  );
  console.log('脚本日志 OK（DEBUG_SUM / DEBUG_END 均已回传）');
  await waitFor(() => events.slice(beforeRun).some((event) => event.event === 'terminated'), 15000, '结束事件');
  const terminated = events.find((event) => event.event === 'terminated');
  console.log('结束事件：', JSON.stringify(terminated));

  console.log('\n== 7) debug_terminate（跑到断点后强制结束）==');
  await conn.debugRun('probe-debug.js', script, [4]);
  await waitFor(() => events.some((event) => event.event === 'stopped' && event.line === 4), 15000, '重新命中第 4 行');
  const beforeTerminate = events.length;
  await conn.debugTerminate();
  await waitFor(
    () => events.slice(beforeTerminate).some((event) => event.event === 'terminated'),
    15000,
    'terminate 后的结束事件',
  );
  console.log('强制结束 OK');
  await conn.stopAll();

  conn.close();
  console.log('\nALL DEBUG E2E CHECKS PASSED');
}

main().catch((error) => {
  console.error('\nDEBUG E2E FAILED:', error);
  process.exitCode = 1;
});
