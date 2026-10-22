import { mutation, queryString, request } from '@/services/apiClient'

const unit = '/v1/unit-admin'
const delegation = id => `/v1/organizations/${encodeURIComponent(id)}/delegation`
export const unitAdministrationApi = {
  delegation: id => request({ url: delegation(id) }),
  preview: (id, body) => mutation('post', `${delegation(id)}/preview`, body),
  apply: (id, body) => mutation('put', delegation(id), body),
  catalog: () => request({ url: `${unit}/catalog` }),
  users: params => request({ url: `${unit}/users${queryString(params)}` }),
  createUser: body => mutation('post', `${unit}/users`, body),
  updateUser: (id, body) => mutation('patch', `${unit}/users/${encodeURIComponent(id)}`, body),
  setUserStatus: (id, body) => mutation('put', `${unit}/users/${encodeURIComponent(id)}/status`, body),
  resetPassword: (id, body) => mutation('post', `${unit}/users/${encodeURIComponent(id)}/reset-password`, body),
  deleteUser: (id, version, reason) => mutation('delete', `${unit}/users/${encodeURIComponent(id)}${queryString({ expected_version: version })}`, { reason }),
  roles: () => request({ url: `${unit}/roles` }),
  role: code => request({ url: `${unit}/roles/${encodeURIComponent(code)}` }),
  createRole: body => mutation('post', `${unit}/roles`, body),
  updateRole: (code, body) => mutation('patch', `${unit}/roles/${encodeURIComponent(code)}`, body),
  updateRolePermissions: (code, body) => mutation('put', `${unit}/roles/${encodeURIComponent(code)}/permissions`, body),
  setRoleStatus: (code, body) => mutation('put', `${unit}/roles/${encodeURIComponent(code)}/status`, body),
  deleteRole: (code, version, reason) => mutation('delete', `${unit}/roles/${encodeURIComponent(code)}${queryString({ expected_version: version })}`, { reason })
}
