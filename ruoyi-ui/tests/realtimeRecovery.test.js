import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h } from 'vue';

/* 实时推送恢复（BUG-07 / ZT-07）：连接卡住由看门狗断开重连，重连、网络恢复后通知页面全部重读；
   后端没在监听时按 15 秒兜底重读；会话被拒（401）后不再用旧会话连接，换会话后订阅立即重连。
   页面重读失败（断网、超时、5xx）按退避重试，没有权限等再读也不会好的错误不重试，恢复时立即再读。 */
const session = vi.hoisted(() => ({ token: 'token-a' }));
vi.mock('@/services/apiClient.js', () => ({ readToken: () => session.token }));

const streams = [];
let nextStatus = 200;
const encoder = new TextEncoder();
function fakeFetch(url, options) {
  const entry = { url, headers: options.headers, signal: options.signal, controller: null };
  streams.push(entry);
  if (nextStatus !== 200) return Promise.resolve(new Response('', { status: nextStatus }));
  const body = new ReadableStream({ start(controller) { entry.controller = controller; } });
  options.signal.addEventListener('abort', () => { try { entry.controller.error(new Error('aborted')); } catch { /* 已关闭 */ } });
  return Promise.resolve(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
}
const send = (entry, text) => entry.controller.enqueue(encoder.encode(text));
const ready = (entry, listening = true) => send(entry, `event: ready\ndata: {"listening":${listening},"at":1}\n\n`);
const change = (entry, topics) => send(entry, `event: change\ndata: ${JSON.stringify({ topics, at: 2 })}\n\n`);
const last = () => streams[streams.length - 1];
async function flush() { for (let i = 0; i < 6; i++) await vi.advanceTimersByTimeAsync(0); }
/* 后端每 20 秒发一次心跳：按心跳节奏推进时间。 */
async function withHeartbeat(ms) { for (let left = ms; left > 0; left -= 20_000) { await vi.advanceTimersByTimeAsync(Math.min(left, 20_000)); send(last(), ':ping\n\n'); await flush(); } }

let service;
const unsubscribes = [];
beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  vi.stubGlobal('fetch', vi.fn(fakeFetch));
  vi.resetModules();
  streams.length = 0; nextStatus = 200; session.token = 'token-a';
  service = await import('@/services/realtime.js');
});
afterEach(() => { unsubscribes.splice(0).forEach(stop => stop()); vi.unstubAllGlobals(); vi.useRealTimers(); });
function subscribe(topics, calls) {
  const stop = service.onDataChange(topics, (received, meta) => calls.push({ topics: received, recovery: !!meta.recovery }));
  unsubscribes.push(stop);
  return stop;
}

describe('实时推送连接恢复', () => {
  it('隐藏标签页不占连接，恢复可见后使用当前会话补读', async () => {
    let hidden = true;
    const visibility = vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
    try {
      const calls = [];
      subscribe(['device'], calls); await flush();
      expect(streams).toHaveLength(0);
      window.dispatchEvent(new Event('online')); await flush();
      expect(streams).toHaveLength(0);
      session.token = 'session-visible'; hidden = false;
      document.dispatchEvent(new Event('visibilitychange')); await flush();
      expect(streams).toHaveLength(1);
      expect(last().headers.Authorization).toBe('Bearer session-visible');
      ready(last()); await flush();
      expect(calls.at(-1)).toEqual({ topics: ['*'], recovery: true });
    } finally { visibility.mockRestore(); }
  });

  it('切到后台立即释放流连接，短暂返回也补读服务端状态', async () => {
    let hidden = false;
    const visibility = vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
    try {
      const calls = [];
      subscribe(['device'], calls); await flush(); ready(last()); await flush();
      hidden = true; document.dispatchEvent(new Event('visibilitychange')); await flush();
      expect(streams[0].signal.aborted).toBe(true);
      expect(service.isRealtimeConnected()).toBe(false);
      await vi.advanceTimersByTimeAsync(120000); await flush();
      expect(streams).toHaveLength(1);
      hidden = false; document.dispatchEvent(new Event('visibilitychange')); await flush();
      expect(streams).toHaveLength(2); ready(last()); await flush();
      expect(calls.at(-1)).toEqual({ topics: ['*'], recovery: true });
    } finally { visibility.mockRestore(); }
  });

  it('连接卡住时看门狗断开重连，连上后通知全部重读', async () => {
    const calls = [];
    subscribe(['device_state'], calls);
    await flush();
    expect(streams).toHaveLength(1);
    expect(streams[0].headers.Authorization).toBe('Bearer token-a');
    ready(streams[0]); await flush();
    expect(calls).toEqual([]);
    expect(service.isRealtimeConnected()).toBe(true);
    change(streams[0], ['device_state']); change(streams[0], ['alarm']); await flush();
    expect(calls).toEqual([{ topics: ['device_state'], recovery: false }]);
    await withHeartbeat(120_000);
    expect(streams).toHaveLength(1);
    // 后端重启后旧连接既不断开也不再有数据：45 秒后断开重连。
    await vi.advanceTimersByTimeAsync(46_000); await flush();
    expect(streams).toHaveLength(2);
    expect(streams[0].signal.aborted).toBe(true);
    ready(streams[1]); await flush();
    expect(calls.at(-1)).toEqual({ topics: ['*'], recovery: true });
  });

  it('后端断开后退避重连并补读；网络恢复时立即重连', async () => {
    const calls = [];
    subscribe(['device'], calls);
    await flush(); ready(streams[0]); await flush();
    streams[0].controller.close(); await flush();
    expect(service.isRealtimeConnected()).toBe(false);
    await vi.advanceTimersByTimeAsync(1_000); await flush();
    expect(streams).toHaveLength(2);
    ready(streams[1]); await flush();
    expect(calls).toEqual([{ topics: ['*'], recovery: true }]);
    window.dispatchEvent(new Event('online')); await flush();
    expect(streams).toHaveLength(3);
    expect(streams[1].signal.aborted).toBe(true);
    ready(streams[2]); await flush();
    expect(calls).toEqual([{ topics: ['*'], recovery: true }, { topics: ['*'], recovery: true }]);
  });

  it('后端没在监听时每 15 秒兜底重读，收到信号后停止', async () => {
    const calls = [];
    subscribe(['device_state'], calls);
    await flush(); ready(streams[0], false); await flush();
    await withHeartbeat(30_000);
    expect(calls.filter(call => call.topics.includes('*'))).toHaveLength(2);
    change(streams[0], ['device_state']); await flush();
    calls.length = 0;
    await withHeartbeat(40_000);
    expect(calls).toEqual([]);
  });

  it('会话被拒后不再用旧会话连接、不兜底重读；换会话后订阅立即重连', async () => {
    const calls = [];
    nextStatus = 401;
    subscribe(['device'], calls);
    await flush();
    expect(streams).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(120_000); await flush();
    expect(streams).toHaveLength(1);
    expect(calls).toEqual([]);
    nextStatus = 200; session.token = 'token-b';
    subscribe(['device_state'], []);
    await flush();
    expect(streams).toHaveLength(2);
    expect(streams[1].headers.Authorization).toBe('Bearer token-b');
  });

  it('页面全部离开后断开连接，不再重连', async () => {
    const stop = subscribe(['device'], []);
    await flush(); ready(streams[0]); await flush();
    stop();
    expect(streams[0].signal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(120_000); await flush();
    expect(streams).toHaveLength(1);
    expect(service.isRealtimeConnected()).toBe(false);
  });
});

describe('页面实时重读', () => {
  function mountHook(reload, minIntervalMs = 100) {
    let api;
    const host = document.createElement('div');
    const app = createApp({ setup() { api = service.useRealtimeRefresh(['device_state'], reload, { minIntervalMs }); return () => h('i'); } });
    app.mount(host);
    unsubscribes.push(() => app.unmount());
    return api;
  }
  const failure = status => Object.assign(new Error('读取失败'), { status });

  it('断网、超时、5xx 值得再读；没有权限、不存在不再读', () => {
    expect([{}, { status: 0 }, { status: 503 }, { status: 408 }, { status: 429 }].map(service.shouldRetryRefresh)).toEqual([true, true, true, true, true]);
    expect([{ status: 401 }, { status: 403 }, { status: 404 }].map(service.shouldRetryRefresh)).toEqual([false, false, false]);
  });

  it('自动刷新失败的原因说得简短：断网、超时、服务不可用，其余用接口说明', () => {
    expect(service.refreshFailureText({ code: 'NETWORK_ERROR', status: 0, message: '暂时无法连接系统，请检查网络。' })).toBe('暂时连不上系统');
    expect(service.refreshFailureText({ code: 'TIMEOUT' })).toBe('服务响应超时');
    expect(service.refreshFailureText({ code: 'REQUEST_FAILED', status: 502, message: '系统暂时无法完成操作。' })).toBe('服务暂时不可用');
    expect(service.refreshFailureText({ code: 'FORBIDDEN', status: 403, message: '当前账号没有权限。' })).toBe('当前账号没有权限');
    expect(service.refreshFailureText({}, '运行趋势加载失败')).toBe('运行趋势加载失败');
  });

  it('暂时失败按 2、4 秒退避重读同一批变化，成功后停止', async () => {
    const runs = [];
    let failures = 2;
    const { trigger } = mountHook(async topics => { runs.push({ topics, at: Date.now() }); if (failures-- > 0) throw failure(503); });
    const start = Date.now();
    trigger(['device_state']); await flush();
    expect(runs).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1_999); await flush();
    expect(runs).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1); await flush();
    expect(runs).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(4_000); await flush();
    expect(runs.map(run => [run.topics, run.at - start])).toEqual([[['device_state'], 0], [['device_state'], 2_000], [['device_state'], 6_000]]);
    // 30 秒内（推送连接仍在，没有兜底重读）不再重读。
    await vi.advanceTimersByTimeAsync(30_000); await flush();
    expect(runs).toHaveLength(3);
  });

  it('没有权限不重试；恢复信号立即再读、不等退避', async () => {
    let denied = 0;
    mountHook(async () => { denied++; throw failure(403); }).trigger(['device_state']);
    await flush(); await vi.advanceTimersByTimeAsync(30_000); await flush();
    expect(denied).toBe(1);

    const runs = [];
    let fail = true;
    const { trigger } = mountHook(async topics => { runs.push(topics); if (fail) { fail = false; throw failure(0); } });
    trigger(['device_state']); await flush();
    expect(runs).toHaveLength(1);
    trigger(['*'], { recovery: true });
    // 恢复重读只受两次重读的最小间隔（这里 0.1 秒）约束，不等 2 秒退避。
    await vi.advanceTimersByTimeAsync(100); await flush();
    expect(runs.map(topics => [...topics].sort())).toEqual([['device_state'], ['*', 'device_state']]);
  });
});
