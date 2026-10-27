import { mutation, newIdempotencyKey, queryString, request } from '@/services/apiClient'

const groupPath = category => `/v1/automation-rule-groups/${encodeURIComponent(category)}`
const write = (method, url, body, operation) => mutation(method, url, body, { idempotencyKey: newIdempotencyKey(operation) })

export const automationRuleApi = {
  responseOrder: () => request({ url: '/v1/response-order-policy' }),
  saveResponseOrder: body => write('put', '/v1/response-order-policy', body, 'response-order-save'),
  responseOrderHistory: params => request({ url: `/v1/response-order-policy/history${queryString(params)}` }),
  flightAvoidance: () => request({ url: '/v1/flight-avoidance/policy' }),
  saveFlightAvoidance: body => write('put', '/v1/flight-avoidance/policy', body, 'flight-avoidance-save'),
  flightAvoidanceHistory: params => request({ url: `/v1/flight-avoidance/policy/history${queryString(params)}` }),
  deviceFlightDistance: id => request({ url: `/v1/flight-avoidance/devices/${encodeURIComponent(id)}/distance` }),
  saveDeviceFlightDistance: (id, body) => write('put', `/v1/flight-avoidance/devices/${encodeURIComponent(id)}/distance`, body, 'flight-distance-save'),
  group: category => request({ url: groupPath(category) }),
  createRule: (category, body) => write('post', `${groupPath(category)}/rules`, body, 'automation-rule-create'),
  updateRule: (category, id, body) => write('put', `${groupPath(category)}/rules/${encodeURIComponent(id)}`, body, 'automation-rule-update'),
  setRuleEnabled: (category, id, body) => write('patch', `${groupPath(category)}/rules/${encodeURIComponent(id)}/enabled`, body, 'automation-rule-enabled'),
  deleteRule: (category, id, body) => write('delete', `${groupPath(category)}/rules/${encodeURIComponent(id)}`, body, 'automation-rule-delete'),
  updateSettings: (category, body) => write('put', `${groupPath(category)}/settings`, body, 'automation-settings-update'),
  history: (category, params) => request({ url: `${groupPath(category)}/history${queryString(params)}` }),
  runs: (category, params) => request({ url: `${groupPath(category)}/runs${queryString(params)}` })
}
