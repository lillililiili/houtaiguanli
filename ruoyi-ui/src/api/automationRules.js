import { mutation, newIdempotencyKey, queryString, request } from '@/services/apiClient'

const groupPath = category => `/v1/automation-rule-groups/${encodeURIComponent(category)}`
const write = (method, url, body, operation) => mutation(method, url, body, { idempotencyKey: newIdempotencyKey(operation) })

export const automationRuleApi = {
  group: category => request({ url: groupPath(category) }),
  createRule: (category, body) => write('post', `${groupPath(category)}/rules`, body, 'automation-rule-create'),
  updateRule: (category, id, body) => write('put', `${groupPath(category)}/rules/${encodeURIComponent(id)}`, body, 'automation-rule-update'),
  setRuleEnabled: (category, id, body) => write('patch', `${groupPath(category)}/rules/${encodeURIComponent(id)}/enabled`, body, 'automation-rule-enabled'),
  updateSettings: (category, body) => write('put', `${groupPath(category)}/settings`, body, 'automation-settings-update'),
  history: (category, params) => request({ url: `${groupPath(category)}/history${queryString(params)}` }),
  runs: (category, params) => request({ url: `${groupPath(category)}/runs${queryString(params)}` })
}
