import { mutation, queryString, request } from '@/services/apiClient'
const versionPath = id => `/v1/response-plan-versions/${encodeURIComponent(id)}`
const airspacePath = id => `/v1/response-plans/airspaces/${encodeURIComponent(id)}`
export const responsePlanApi = {
  list: params => request({ url: `/v1/response-plans${queryString(params)}` }),
  versions: id => request({ url: `/v1/response-plans/${encodeURIComponent(id)}/versions` }),
  airspaces: params => request({ url: `/v1/response-plans/airspace-options${queryString(params)}` }),
  create: body => mutation('post', '/v1/response-plans', body),
  newVersion: (id, body) => mutation('post', `/v1/response-plans/${encodeURIComponent(id)}/versions`, body),
  update: (id, body) => mutation('patch', versionPath(id), body),
  publish: (id, body) => mutation('post', `${versionPath(id)}/publish`, body),
  withdraw: (id, body) => mutation('post', `${versionPath(id)}/withdraw`, body),
  airspace: (id, params) => request({ url: `${airspacePath(id)}${queryString(params)}` }),
  bind: (id, body) => mutation('put', airspacePath(id), body)
}
