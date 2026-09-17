import { mutation, queryString, request } from '@/services/apiClient'

const path = (root, id) => `${root}/${encodeURIComponent(id)}`
const list = (url, params) => request({ url: `${url}${queryString(params)}` })
export const directoryApi = {
  profiles: params => list('/v1/organization-profiles', params),
  profile: id => request({ url: path('/v1/organization-profiles', id) }),
  saveProfile: (id, body) => mutation(id ? 'patch' : 'post', id ? path('/v1/organization-profiles', id) : '/v1/organization-profiles', body),
  contacts: params => list('/v1/contacts', params),
  contact: id => request({ url: path('/v1/contacts', id) }),
  saveContact: (id, body) => mutation(id ? 'patch' : 'post', id ? path('/v1/contacts', id) : '/v1/contacts', body),
  bindings: params => list('/v1/plan-source-bindings', params),
  saveBinding: (id, body) => mutation(id ? 'patch' : 'post', id ? path('/v1/plan-source-bindings', id) : '/v1/plan-source-bindings', body),
  options: params => list('/v1/directory-options', params),
  subjects: id => request({ url: `${path('/v1/flight-plans', id)}/subjects` }),
  saveSubjects: (id, body) => mutation('patch', `${path('/v1/flight-plans', id)}/subjects`, body),
  settings: params => list('/v1/notification-settings', params),
  setting: id => request({ url: path('/v1/notification-settings', id) }),
  saveSetting: (id, body) => mutation(id ? 'patch' : 'post', id ? path('/v1/notification-settings', id) : '/v1/notification-settings', body),
  diagnostics: id => request({ url: `${path('/v1/notification-settings', id)}/diagnostics` })
}
