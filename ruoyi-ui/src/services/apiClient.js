import axios from 'axios';

export const SESSION_KEY = 'uav.admin.session.v1';
const baseURL = String(import.meta.env.VITE_APP_BASE_API || (import.meta.env.DEV ? '/dev-api' : '/api')).replace(/\/$/, '');

export class ApiError extends Error {
  constructor(message, code = 'REQUEST_FAILED', status = 0) {
    super(message || '请求失败');
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
  const token = readToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

function normalizeError(error) {
  if (error instanceof ApiError) return error;
  const response = error?.response;
  const payload = response?.data?.error || {};
  if (response?.status === 401) window.dispatchEvent(new CustomEvent('admin:unauthorized'));
  if (!response) return new ApiError('无法连接后端服务，请确认服务已启动后重试。', 'NETWORK_ERROR', 0);
  return new ApiError(payload.message || `请求失败（HTTP ${response.status}）`, payload.code || 'REQUEST_FAILED', response.status);
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
