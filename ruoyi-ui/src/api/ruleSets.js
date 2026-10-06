import { mutation, newIdempotencyKey, queryString, request } from '@/services/apiClient'

const setPath = code => `/v1/rule-sets/${encodeURIComponent(code)}`

/** 研判规则集（合法性、空间安全风险）：读取需要 rule:read，启用需要 rule:read + rule:manage。 */
export const ruleSetApi = {
  list: (params = { page: 1, size: 100 }) => request({ url: `/v1/rule-sets${queryString(params)}` }),
  versions: (code, params = { page: 1, size: 100 }) => request({ url: `${setPath(code)}/versions${queryString(params)}` }),
  activate: (code, body, key = newIdempotencyKey('rule-set-activate')) => mutation('post', `${setPath(code)}/activate`, body, { idempotencyKey: key })
}
