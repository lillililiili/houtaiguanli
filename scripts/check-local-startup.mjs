#!/usr/bin/env node
// Node >= 20. No credentials, dependency installs, writes to business data, or process termination.
import { createHash } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
export const sourceId = directory => createHash('sha256').update(path.resolve(directory)).digest('hex');
function requireFact(condition, message) { if (!condition) throw new Error(message); }
async function request(url, options = {}) {
  const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(8000) });
  return { status: response.status, type: response.headers.get('content-type') || '', cacheControl: response.headers.get('cache-control') || '', body: await response.text() };
}
function json(response, status, name) {
  requireFact(response.status === status && /^application\/(?:[\w.-]+\+)?json(?:;|$)/i.test(response.type), `${name}：预期 HTTP ${status} JSON，实际 HTTP ${response.status} ${response.type}`);
  try { return JSON.parse(response.body); } catch { throw new Error(`${name}：无效 JSON`); }
}
function metadata(html, name) {
  for (const tag of html.matchAll(/<meta\b[^>]*>/gi)) {
    const attrs = Object.fromEntries([...tag[0].matchAll(/([\w-]+)\s*=\s*["']([^"']*)["']/g)].map(match => [match[1], match[2]]));
    if (attrs.name === name) return attrs.content;
  }
  return '';
}
function sameOriginUrl(value, origin, { allowSearch = false } = {}) {
  const url = new URL(value, origin);
  requireFact(url.origin === new URL(origin).origin && !url.username && !url.password && !url.hash, '检查仅支持页面同源地址；请明确配置本地代理');
  requireFact(allowSearch || !url.search, 'API 或地图配置地址不能包含查询参数');
  return url;
}

export async function checkFrontend(origin, directory, clientType) {
  const page = await request(origin);
  requireFact(page.status === 200 && page.type.includes('text/html'), '首页不是 HTTP 200 HTML');
  const apiBase = metadata(page.body, 'uav-api-base');
  const mode = metadata(page.body, 'uav-runtime-mode');
  requireFact(apiBase && ['built', 'dev'].includes(mode), '缺少启动检查元信息，请用当前代码重新构建并重启');
  requireFact(metadata(page.body, 'uav-source-id') === sourceId(directory), '页面并非来自指定代码目录，请核对监听进程和工作目录');
  const apiUrl = sameOriginUrl(apiBase, origin).href.replace(/\/$/, '');
  if (mode === 'built') {
    const localIndex = await readFile(path.join(directory, 'dist/index.html'), 'utf8');
    requireFact(localIndex === page.body, '首页与指定目录的构建产物不同，请重启正确目录的服务');
  }
  const resources = [
    ...[...page.body.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["']/gi)].map(match => match[1]),
    ...[...page.body.matchAll(/<link\b(?=[^>]*\brel=["'](?:stylesheet|modulepreload)["'])[^>]*\bhref=["']([^"']+)["']/gi)].map(match => match[1])
  ];
  requireFact(resources.length > 0, '首页没有可检查的静态资源');
  for (const resource of new Set(resources)) {
    // Vite HMR and versioned assets carry cache queries; fetch that exact same-origin resource.
    // API/map metadata stays query-free, and built assets still match their local file contents.
    const url = sameOriginUrl(resource, origin, { allowSearch: true });
    const response = await request(url);
    requireFact(response.status === 200 && /javascript|css/.test(response.type) && !/^\s*(?:<!doctype|<html)/i.test(response.body), `静态资源不可用：${url.pathname}（HTTP ${response.status} ${response.type}）`);
    if (mode === 'built') {
      const root = path.resolve(directory, 'dist');
      const file = path.resolve(root, `.${decodeURIComponent(url.pathname)}`);
      requireFact(file.startsWith(`${root}${path.sep}`), '静态资源路径超出构建目录');
      requireFact(await readFile(file, 'utf8') === response.body, `静态资源与本地构建产物不同：${url.pathname}`);
    }
  }
  const headers = { 'Content-Type': 'application/json', Origin: new URL(origin).origin, 'X-Client-Type': clientType };
  const login = json(await request(`${apiUrl}/auth/login`, { method: 'POST', headers, body: '{}' }), 400, '登录代理');
  requireFact(login.ok === false && login.error?.code === 'VALIDATION_ERROR', '登录代理未返回预期参数校验错误');
  const me = json(await request(`${apiUrl}/auth/me`, { headers }), 401, '未登录拦截');
  requireFact(me.ok === false && typeof me.error?.code === 'string', '未登录拦截未返回错误契约');
  return { api_base: apiBase, mode, directory: path.resolve(directory), resources: new Set(resources).size };
}

export async function checkReadiness(origin) {
  const health = json(await request(`${origin}/actuator/health/readiness`), 200, '后端就绪');
  requireFact(health.status === 'UP', '后端尚未就绪（status 不是 UP）');
  return { status: health.status };
}
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
export async function checkMap(origin) {
  const pointer = await request(`${origin}/map-data/control/map-config.json`);
  // Only a missing pointer means the built-in map is the expected active map.
  // A broken published pointer must fail verification instead of hiding behind the fallback.
  const managed = ![204, 404].includes(pointer.status);
  const config = managed ? json(pointer, 200, '已启用地图运行指针')
    : json(await request(`${origin}/map-config.json`), 200, '内置地图代理');
  requireFact(typeof config.manifest === 'string' && config.manifest.trim(), '地图配置缺少 manifest');
  if (managed) {
    requireFact(typeof config.package_id === 'string' && config.package_id.trim() &&
      Number.isSafeInteger(config.revision) && config.revision > 0, '已启用地图指针缺少有效版本与修订号');
    requireFact(/(?:^|,)\s*no-store(?:\s|,|$)/i.test(pointer.cacheControl), '已启用地图运行指针必须禁止缓存（no-store）');
  }
  const manifestUrl = sameOriginUrl(config.manifest, origin);
  const manifest = json(await request(manifestUrl), 200, '地图 manifest');
  requireFact(manifest.version === 1 && manifest.coordinateSystem === 'WGS84' &&
    typeof manifest.archive === 'string' && typeof manifest.style === 'string', '地图 manifest 契约不完整');
  return {
    manifest: manifestUrl.pathname, managed,
    package_id: managed ? config.package_id : null,
    revision: managed ? config.revision : null,
    city_code: managed ? config.city_code : null,
    clear_business_overlays: managed ? Boolean(config.clear_business_overlays) : false,
    manifest_sha256: createHash('sha256').update(JSON.stringify(canonical(manifest))).digest('hex')
  };
}
export function compareMaps(business, admin) {
  requireFact(JSON.stringify(canonical(business)) === JSON.stringify(canonical(admin)), '前后台生效地图配置、版本或 manifest 内容不一致');
  return business;
}
export async function checkSimulator(origin) {
  const page = await request(origin);
  requireFact(page.status === 200 && page.type.includes('text/html'), '模拟器首页不可用');
  const status = json(await request(`${origin}/api/status`), 200, '模拟器状态');
  requireFact(status && typeof status.phase === 'string' && ['realtime', 'isolated_qa'].includes(status.scene_mode) &&
    Number.isSafeInteger(status.mqtt_reconnect_count) && status.mqtt_reconnect_count >= 0, '模拟器状态契约缺少有效 phase/scene_mode/mqtt_reconnect_count');
  requireFact(status.phase !== 'FAILED', '模拟任务已失败（phase=FAILED），请查看模拟器错误并恢复后重新检查');
  requireFact(['IDLE', 'STOPPED', 'COMPLETED', 'RUNNING', 'PAUSED'].includes(status.phase), `模拟任务尚未就绪或状态未知（phase=${status.phase}）`);
  const sceneActive = ['RUNNING', 'PAUSED'].includes(status.phase);
  const realtime = status.realtime;
  const now = Date.now();
  // Both loops verify their session every 5 seconds. Use the existing 30-second
  // receiver lease/reconnect window to reject stale health without requiring traffic.
  const recentSession = value => Number.isSafeInteger(value) && value > 0 && value <= now && now - value <= 30_000;
  if (sceneActive) {
    requireFact(status.error === '', '模拟任务仍有运行错误，不能确认运行健康');
    requireFact(status.connected === true && status.mqtt_ready === true && status.mqtt_connected === true, '模拟任务正在运行或暂停，但平台会话或 MQTT 未连接');
    requireFact(recentSession(status.session_verified_at), '模拟任务会话核验缺失或已超过 30 秒，不能确认当前健康');
    if (status.scene_mode === 'realtime') requireFact(realtime?.state === 'RUNNING', '模拟任务的实时收发服务未运行');
  }
  if (realtime != null) {
    requireFact(['STOPPED', 'RUNNING'].includes(realtime.state), `实时收发服务已失败、尚未就绪或状态未知（state=${realtime.state}）`);
    if (realtime.state === 'RUNNING') {
      requireFact(realtime.error === '' && status.connected === true, '实时收发服务有运行错误或平台会话未连接');
      requireFact(recentSession(realtime.session_verified_at), '实时收发会话核验缺失或已超过 30 秒，不能确认当前健康');
      requireFact(typeof realtime.config?.notifications_enabled === 'boolean' && typeof realtime.config?.countermeasure_enabled === 'boolean', '实时收发状态缺少有效的通知/指令启用配置');
      if (realtime.config.notifications_enabled) {
        const notifications = realtime.notifications;
        requireFact(notifications && !notifications.last_error && Number.isSafeInteger(notifications.lease_expires_at) &&
          notifications.lease_expires_at > now, '通知接收器有错误或租约无效，不能确认当前健康');
      }
      if (realtime.config.countermeasure_enabled) requireFact(realtime.countermeasure?.listening === true, '已启用的设备指令接收器没有监听');
    }
  }
  return {
    phase: status.phase, scene_mode: status.scene_mode, mqtt_reconnect_count: status.mqtt_reconnect_count,
    scene_active: sceneActive, mqtt_connected: status.mqtt_connected === true,
    realtime_state: realtime?.state ?? null,
    session_verified_at: sceneActive ? status.session_verified_at : null,
    realtime_session_verified_at: realtime?.state === 'RUNNING' ? realtime.session_verified_at : null
  };
}

export async function runChecks(options) {
  const results = [];
  async function check(name, action) {
    try { const detail = await action(); results.push({ name, status: 'PASS', detail }); return detail; }
    catch (error) { results.push({ name, status: 'FAIL', detail: error.message }); return null; }
  }
  await check('后端就绪', () => checkReadiness(options.api));
  await check('管理端页面、构建来源、资源和真实 API 代理', () => checkFrontend(options.admin, options.adminDir, 'BACKEND'));
  if (!options.skipBusiness) {
    await check('业务前台页面、构建来源、资源和真实 API 代理', () => checkFrontend(options.business, options.businessDir, 'FRONTEND'));
    const business = await check('业务前台地图', () => checkMap(options.business));
    const admin = await check('管理端地图代理', () => checkMap(options.admin));
    if (business && admin) await check('前后台地图配置一致', async () => compareMaps(business, admin));
  }
  if (!options.skipSimulator) await check('模拟器首页和状态', () => checkSimulator(options.simulator));
  return { checked_at: new Date().toISOString(), passed: results.every(r => r.status === 'PASS'), account_login: 'NOT_TESTED', results };
}

async function main() {
  const { values } = parseArgs({ options: {
    api: { type: 'string', default: 'http://127.0.0.1:8081' },
    admin: { type: 'string', default: 'http://127.0.0.1:5175' },
    business: { type: 'string', default: 'http://127.0.0.1:5173' },
    simulator: { type: 'string', default: 'http://127.0.0.1:8766' },
    'admin-dir': { type: 'string', default: path.join(repoRoot, 'ruoyi-ui') },
    'business-dir': { type: 'string' },
    'skip-business': { type: 'boolean', default: false },
    'skip-simulator': { type: 'boolean', default: false },
    report: { type: 'string', default: path.join(repoRoot, 'artifacts/local-runtime/startup-check.json') }
  } });
  requireFact(values['skip-business'] || values['business-dir'], '必须显式指定 --business-dir，或通过 --skip-business 记录本次不检查业务前台');
  const report = await runChecks({ ...values, adminDir: values['admin-dir'], businessDir: values['business-dir'], skipBusiness: values['skip-business'], skipSimulator: values['skip-simulator'] });
  report.scope = { business: !values['skip-business'], simulator: !values['skip-simulator'] };
  for (const result of report.results) console.log(`[${result.status}] ${result.name}${result.status === 'FAIL' ? `：${result.detail}` : ''}`);
  await mkdir(path.dirname(path.resolve(values.report)), { recursive: true });
  await writeFile(values.report, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`报告：${path.resolve(values.report)}`);
  console.log(report.passed ? '所选范围自动启动检查通过；真实账号登录未验证。' : '启动检查失败，不能宣布启动完成；按失败项修复后重新检查。');
  process.exitCode = report.passed ? 0 : 1;
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => { console.error(`[FAIL] ${error.message}`); process.exitCode = 1; });
}
