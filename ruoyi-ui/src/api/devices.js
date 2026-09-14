import { mutation, newIdempotencyKey, queryString, request } from '@/services/apiClient.js';

export const deviceApi = {
  list: params => request({ url: `/v1/devices${queryString(params)}` }),
  options: () => request({ url: '/v1/devices/options' }),
  mqttOptions: () => request({ url: '/v1/devices/mqtt-options' }),
  detail: id => request({ url: `/v1/devices/${encodeURIComponent(id)}` }),
  onboard: (body, key = newIdempotencyKey('device-onboard')) => mutation('post', '/v1/devices/onboard', body, { idempotencyKey: key }),
  update: (id, body, key = newIdempotencyKey('device-update')) => mutation('put', `/v1/devices/${encodeURIComponent(id)}`, body, { idempotencyKey: key }),
  setEnabled: (id, body, key = newIdempotencyKey('device-enabled')) => mutation('patch', `/v1/devices/${encodeURIComponent(id)}/enabled`, body, { idempotencyKey: key }),
  overview: () => request({ url: '/v1/device-monitor/overview' }),
  tree: params => request({ url: `/v1/device-monitor/tree${queryString(params)}` }),
  state: id => request({ url: `/v1/devices/${encodeURIComponent(id)}/state` }),
  history: (id, params) => request({ url: `/v1/devices/${encodeURIComponent(id)}/state-history${queryString(params)}` }),
  incidents: params => request({ url: `/v1/device-incidents${queryString(params)}` }),
  events: params => request({ url: `/v1/device-events${queryString(params)}` }),
  command: id => request({ url: `/v1/device-commands/${encodeURIComponent(id)}` }),
  protocolStatus: id => request({ url: `/v1/devices/${encodeURIComponent(id)}/protocol-status` }),
  targets: params => request({ url: `/v1/sensing/targets${queryString(params)}` })
};

export const integrationApi = {
  protocols: () => request({ url: '/v1/device-protocols' })
};

export const mqttApi = {
  list: () => request({ url: '/v1/mqtt-brokers' }),
  options: () => request({ url: '/v1/devices/mqtt-options' }),
  scopes: () => request({ url: '/v1/mqtt-brokers/scopes' }),
  create: (body, key = newIdempotencyKey('mqtt-create')) => mutation('post', '/v1/mqtt-brokers', body, { idempotencyKey: key }),
  update: (id, body, key = newIdempotencyKey('mqtt-update')) => mutation('put', `/v1/mqtt-brokers/${encodeURIComponent(id)}`, body, { idempotencyKey: key }),
  setEnabled: (id, body, key = newIdempotencyKey('mqtt-enabled')) => mutation('patch', `/v1/mqtt-brokers/${encodeURIComponent(id)}/enabled`, body, { idempotencyKey: key })
};

export const commissionApi = {
  list: params => request({ url: `/v1/commission-tasks${queryString(params)}` }),
  get: id => request({ url: `/v1/commission-tasks/${encodeURIComponent(id)}` }),
  create: body => mutation('post', '/v1/commission-tasks', body),
  connect: (id, version) => mutation('post', `/v1/commission-tasks/${encodeURIComponent(id)}/connect`, { version }),
  configure: (id, body) => mutation('put', `/v1/commission-tasks/${encodeURIComponent(id)}/configuration`, body),
  start: (id, version) => mutation('post', `/v1/commission-tasks/${encodeURIComponent(id)}/start`, { version }),
  cancel: (id, version) => mutation('post', `/v1/commission-tasks/${encodeURIComponent(id)}/cancel`, { version }),
  events: (id, params) => request({ url: `/v1/commission-tasks/${encodeURIComponent(id)}/events${queryString(params)}` }),
  report: id => request({ url: `/v1/commission-tasks/${encodeURIComponent(id)}/report` })
};
