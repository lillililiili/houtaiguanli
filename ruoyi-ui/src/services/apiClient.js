import axios from 'axios';
import { userFacingMessage } from '@/utils/userMessages';

export const SESSION_KEY = 'uav.admin.session.v1';
const baseURL = String(import.meta.env.VITE_APP_BASE_API || (import.meta.env.DEV ? '/dev-api' : '/api')).replace(/\/$/, '');

export class ApiError extends Error {
  constructor(message, code = 'REQUEST_FAILED', status = 0) {
    super(userFacingMessage(message || '请求失败'));
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
  }
}

export function readToken() {
  try { return sessionStorage.getItem(SESSION_KEY) || ''; } catch { return ''; }
}

export function writeToken(token) {
  try {
    if (token) sessionStorage.setItem(SESSION_KEY, token);
    else sessionStorage.removeItem(SESSION_KEY);
  } catch { /* 浏览器禁用存储时只保留当前 Pinia 会话。 */ }
}

export function newIdempotencyKey(prefix = 'admin') {
  const id = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `${prefix}-${id}`;
}

const client = axios.create({ baseURL, timeout: 15000 });

client.interceptors.request.use(config => {
  config.headers['X-Client-Type'] = 'BACKEND';
  const token = readToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// 登录、退出返回 401 不是会话过期：登录是账号或密码不对，退出时会话本来就要作废。
const SESSION_FREE_URLS = new Set(['/v1/auth/login', '/v1/auth/logout']);

function notifyUnauthorized(config = {}) {
  if (SESSION_FREE_URLS.has(config.url)) return;
  const method = String(config.method || 'get').toLowerCase();
  // submitting：被拒的是提交类请求，界面要告诉用户这次没有保存。
  window.dispatchEvent(new CustomEvent('admin:unauthorized', { detail: { submitting: method !== 'get' && method !== 'head' } }));
}

function normalizeError(error) {
  if (error instanceof ApiError) return error;
  const response = error?.response;
  const payload = response?.data?.error || {};
  if (payload.code === 'BACKEND_ACCESS_DENIED' && !SESSION_FREE_URLS.has(error.config?.url || response.config?.url)) {
    window.dispatchEvent(new CustomEvent('admin:access-denied'));
  }
  if (response?.status === 401) notifyUnauthorized(error.config || response.config);
  if (!response) return new ApiError('暂时无法连接系统，请检查网络；若刚提交过操作，请先核对最新记录，避免重复提交。', 'NETWORK_ERROR', 0);
  return new ApiError(payload.message || '系统暂时无法完成操作，请查看最新记录；仍有问题请联系管理员。', payload.code || 'REQUEST_FAILED', response.status);
}

client.interceptors.response.use(response => {
  if (response.config.responseType === 'blob') return response;
  const envelope = response.data;
  if (envelope?.ok !== true) throw new ApiError(envelope?.error?.message || '服务响应格式无效', envelope?.error?.code || 'INVALID_RESPONSE', response.status);
  return envelope.data;
}, async error => {
  if (error?.response?.data instanceof Blob) {
    try { error.response.data = JSON.parse(await error.response.data.text()); } catch { /* Non-JSON download error. */ }
  }
  return Promise.reject(normalizeError(error));
});

export function request(config) {
  return client(config).catch(error => { throw normalizeError(error); });
}

export function mutation(method, url, data, options = {}) {
  const headers = { ...(options.headers || {}), 'Idempotency-Key': options.idempotencyKey || newIdempotencyKey() };
  return request({ method, url, data, ...options, headers });
}

export function queryString(values = {}) {
  const search = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== '' && value !== null && value !== undefined) search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export async function download(url, filename, options = {}) {
  let response;
  try { response = await client.get(url, { ...options, responseType: 'blob' }); }
  catch (error) { throw normalizeError(error); }
  const objectUrl = URL.createObjectURL(response.data);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = filename || 'download';
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(objectUrl);
}

export function isUncertainOutcome(error) {
  return error?.status === 409 || error?.code === 'NETWORK_ERROR' || error?.code === 'TIMEOUT';
}
