/* 实时数据变化信号（后端 /api/v1/realtime/events，SSE）。
   后端只推“哪类数据变了”（alarm/target/legality/risk/plan/airspace/device/device_state/disposal/evidence/punishment，
   "*" 表示全部），页面收到后经原有接口重读，权限仍由读取接口控制。
   EventSource 不能带 Bearer 头，这里用 fetch 读流；断线指数退避重连。
   - 看门狗：后端每 20 秒发一次心跳，45 秒内既没有信号也没有心跳（例如后端重启后开发代理没有关掉旧连接）就主动断开重连。
   - 补读：重连成功、网络恢复（online）、页面在后台停留较久后回到前台时，通知页面全部重读（"*"，带 recovery 标记），
     补上期间可能错过的变化，上次重读失败的页面也立即再读。
   - 推送不可用（连不上，或后端暂时没有监听数据库）时退回每 15 秒通知一次重读，页面不会停在旧数据上。
   - 没有登录会话或推送接口答复会话已失效（401）时不连接、不轮询，登录状态变化后再连。 */
import { onMounted, onUnmounted } from 'vue';
import { readToken as readSessionToken } from './apiClient.js';

const API_BASE = `${String(import.meta.env.VITE_APP_BASE_API || (import.meta.env.DEV ? '/dev-api' : '/api')).replace(/\/$/, '')}/v1`;
const FALLBACK_MS = 15_000;
const STALL_MS = 45_000;
const HIDDEN_RESYNC_MS = 60_000;
const MAX_BACKOFF_MS = 30_000;
const RETRY_MIN_MS = 2_000;
const RETRY_MAX_MS = 30_000;

const subscribers = new Set();
let controller = null;
let connectedToken = '';
let connected = false;
let listening = false;
let retryTimer = null;
let fallbackTimer = null;
let stallTimer = null;
let backoff = 1_000;
let started = false;
let hadConnection = false;
let missed = false;
let rejectedToken = '';
let lastDataAt = 0;
let hiddenAt = 0;

function emit(topics, meta = {}) {
  const set = new Set(topics);
  subscribers.forEach(sub => {
    if (set.has('*') || sub.topics.some(topic => set.has(topic))) sub.notify([...set], meta);
  });
}

function pollFallback() {
  const token = readSessionToken();
  // 没有会话或会话已被拒绝时重读也只会得到 401，等登录后再读。
  if (token && token !== rejectedToken) emit(['*']);
}

/* connected：推送连接已建立；listening：后端正在监听数据库变化。两者都满足才停掉兜底重读。 */
function setState(open, live = open) {
  connected = open;
  listening = open && live;
  if (listening || !subscribers.size) {
    clearInterval(fallbackTimer);
    fallbackTimer = null;
  } else if (!fallbackTimer) {
    fallbackTimer = setInterval(pollFallback, FALLBACK_MS);
  }
}

function scheduleReconnect(delay = backoff) {
  clearTimeout(retryTimer);
  retryTimer = setTimeout(connect, delay);
  backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
}

function stopStream() {
  clearTimeout(retryTimer);
  retryTimer = null;
  clearTimeout(stallTimer);
  stallTimer = null;
  const current = controller;
  controller = null;
  current?.abort();
}

function armWatchdog(current) {
  lastDataAt = Date.now();
  clearTimeout(stallTimer);
  stallTimer = setTimeout(() => {
    if (controller !== current) return;
    // 超过两次心跳都没收到任何数据：连接已经失效，断开后马上重连，连上后全部重读。
    stopStream();
    missed = true;
    setState(false);
    backoff = 1_000;
    scheduleReconnect(0);
  }, STALL_MS);
}

function handleEvent(block) {
  let name = 'message';
  const data = [];
  block.split('\n').forEach(line => {
    if (line.startsWith('event:')) name = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  });
  if (name === 'ready') {
    let ready = {};
    try { ready = JSON.parse(data.join('\n') || '{}') || {}; } catch { /* 旧版本 ready 没有内容 */ }
    backoff = 1_000;
    // 后端暂时没有监听数据库时连接虽在也收不到信号，继续按 15 秒兜底重读。
    setState(true, ready.listening !== false);
    // 首次连接时页面刚加载过数据；重连或此前连接失败过才可能错过信号，需要全部重读。
    if (hadConnection || missed) emit(['*'], { recovery: true });
    hadConnection = true;
    missed = false;
  } else if (name === 'change') {
    if (!listening) setState(true, true);
    try {
      const topics = JSON.parse(data.join('\n')).topics || [];
      // 后端重新连上数据库时会发 "*"，同样视为恢复，失败退避中的页面立即重读。
      emit(topics, { recovery: topics.includes('*') });
    } catch { /* 忽略无法解析的信号 */ }
  }
}

async function connect() {
  stopStream();
  const token = readSessionToken();
  connectedToken = token;
  if (!subscribers.size) { setState(false); return; }
  if (!token || token === rejectedToken) {
    // 不发请求；重新登录后页面重新订阅会立即重连，这里只是定期检查会话是否已更换。
    setState(false);
    scheduleReconnect(MAX_BACKOFF_MS);
    return;
  }
  const current = new AbortController();
  controller = current;
  // 连接请求本身卡住（例如代理迟迟不答复）同样由看门狗断开重试。
  armWatchdog(current);
  try {
    const response = await fetch(`${API_BASE}/realtime/events`, {
      headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
      cache: 'no-store',
      signal: current.signal
    });
    if (response.status === 401) rejectedToken = token;
    if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
    armWatchdog(current);
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';
    for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
      if (controller !== current) return;
      armWatchdog(current);
      buffer += chunk.value.replace(/\r\n/g, '\n');
      let index;
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        handleEvent(buffer.slice(0, index));
        buffer = buffer.slice(index + 2);
      }
    }
  } catch {
    if (current.signal.aborted) return;
  }
  if (controller !== current) return;
  controller = null;
  clearTimeout(stallTimer);
  stallTimer = null;
  missed = true;
  setState(false);
  scheduleReconnect();
}

function reconnectNow() {
  missed = true;
  backoff = 1_000;
  connect();
}

function ensureStarted() {
  if (started || typeof window === 'undefined') return;
  started = true;
  // 网络恢复：旧连接可能已失效，断网期间的重读也可能失败过，重新连接后全部重读。
  window.addEventListener('online', () => { if (subscribers.size) reconnectNow(); });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { hiddenAt = Date.now(); return; }
    const away = hiddenAt ? Date.now() - hiddenAt : 0;
    hiddenAt = 0;
    if (!subscribers.size) return;
    // 后台标签页的计时器会被浏览器推迟，回到前台时先确认连接仍在收数据。
    if (!controller || Date.now() - lastDataAt > STALL_MS) reconnectNow();
    else if (away >= HIDDEN_RESYNC_MS) emit(['*'], { recovery: true });
  });
}

/** 订阅一组数据类别的变化；handler(topics, { recovery })，recovery 表示重连或网络恢复后的全部重读。
    返回取消函数。登录会话变化后的下一次订阅按新会话重连。 */
export function onDataChange(topics, handler) {
  ensureStarted();
  const sub = { topics: Array.isArray(topics) ? topics : [topics], notify: handler };
  subscribers.add(sub);
  if (subscribers.size === 1 || !controller || readSessionToken() !== connectedToken) { backoff = 1_000; connect(); }
  else if (!listening) setState(connected, false);
  return () => {
    subscribers.delete(sub);
    if (!subscribers.size) {
      stopStream();
      clearInterval(fallbackTimer);
      fallbackTimer = null;
      connected = false;
      listening = false;
      hadConnection = false;
      missed = false;
    }
  };
}

export function isRealtimeConnected() { return connected; }

/** 断网、超时或服务暂时不可用（5xx）值得再读；登录失效、没有权限、记录不存在等再读也不会好。 */
export function shouldRetryRefresh(error) {
  const status = Number(error?.status) || 0;
  return !status || status === 408 || status === 429 || status >= 500;
}

/** 自动刷新失败时给用户看的简短原因（接在“自动刷新失败”后面）：断网、服务暂不可用说清楚，其余用接口的说明。 */
export function refreshFailureText(error, fallback = '读取失败') {
  if (error?.code === 'NETWORK_ERROR') return '暂时连不上系统';
  if (error?.code === 'TIMEOUT') return '服务响应超时';
  if (Number(error?.status) >= 500) return '服务暂时不可用';
  return String(error?.message || fallback).replace(/[。.！!；;，,\s]+$/, '');
}

/* 页面订阅数据变化并重读：同一时刻只跑一次，期间的信号合并成结束后的一次；两次重读至少间隔
   minIntervalMs；页面隐藏时不读，回到前台再补。reload(topics) 应保留用户的筛选、分页和选中项，
   读取失败时应抛出错误（页面自己负责显示）。断网、超时或 5xx 失败后按 2、4、8……最长 30 秒退避
   再读同一批变化；重连或网络恢复（recovery）时立即再读，不等退避结束。 */
export function useRealtimeRefresh(topics, reload, { minIntervalMs = 1_000 } = {}) {
  let running = false;
  let lastAt = 0;
  let timer = null;
  let stop = null;
  let changed = new Set();
  let retryDelay = 0;
  let retryAt = 0;

  async function run() {
    timer = null;
    if (document.hidden || running || !changed.size) return;
    const wait = Math.max(lastAt + minIntervalMs, retryAt) - Date.now();
    if (wait > 0) { timer = setTimeout(run, wait); return; }
    running = true;
    lastAt = Date.now();
    const batch = [...changed];
    changed = new Set();
    try {
      await reload(batch);
      retryDelay = 0;
      retryAt = 0;
    } catch (error) {
      // 页面自己的 reload 负责显示错误；暂时性失败把这批变化留到退避后再读。
      if (shouldRetryRefresh(error)) {
        batch.forEach(topic => changed.add(topic));
        retryDelay = Math.min(retryDelay ? retryDelay * 2 : RETRY_MIN_MS, RETRY_MAX_MS);
        retryAt = Date.now() + retryDelay;
      }
    } finally {
      running = false;
      if (changed.size && !timer) timer = setTimeout(run, 0);
    }
  }

  function trigger(received = ['*'], { recovery = false } = {}) {
    received.forEach(topic => changed.add(topic));
    if (recovery) {
      retryDelay = 0;
      retryAt = 0;
      if (timer && !running) { clearTimeout(timer); timer = null; }
    }
    if (!timer && !running) timer = setTimeout(run, 0);
  }
  function onVisible() { if (!document.hidden && changed.size) trigger([]); }

  onMounted(() => {
    stop = onDataChange(topics, trigger);
    document.addEventListener('visibilitychange', onVisible);
  });
  onUnmounted(() => {
    stop?.();
    clearTimeout(timer);
    document.removeEventListener('visibilitychange', onVisible);
  });
  return { trigger };
}
