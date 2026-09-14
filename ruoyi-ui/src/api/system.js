import { download, mutation, queryString, request } from '@/services/apiClient.js';

export const systemApi = {
  users: params => request({ url: `/v1/users${queryString(params)}` }),
  user: id => request({ url: `/v1/users/${encodeURIComponent(id)}` }),
  createUser: body => mutation('post', '/v1/users', body),
  updateUser: (id, body) => mutation('patch', `/v1/users/${encodeURIComponent(id)}`, body),
  setUserStatus: (id, body) => mutation('put', `/v1/users/${encodeURIComponent(id)}/status`, body),
  resetPassword: (id, body) => mutation('post', `/v1/users/${encodeURIComponent(id)}/reset-password`, body),
  deleteUser: (id, version, reason) => mutation('delete', `/v1/users/${encodeURIComponent(id)}${queryString({ expected_version: version })}`, { reason }),
  organizations: () => request({ url: '/v1/organizations' }),
  createOrganization: body => mutation('post', '/v1/organizations', body),
  updateOrganization: (id, body) => mutation('patch', `/v1/organizations/${encodeURIComponent(id)}`, body),
  roles: () => request({ url: '/v1/roles' }),
  role: code => request({ url: `/v1/roles/${encodeURIComponent(code)}` }),
  permissions: () => request({ url: '/v1/permissions/catalog' }),
  permissionActions: () => request({ url: '/v1/permissions/actions' }),
  createRole: body => mutation('post', '/v1/roles', body),
  updateRole: (code, body) => mutation('patch', `/v1/roles/${encodeURIComponent(code)}`, body),
  updateRolePermissions: (code, body) => mutation('put', `/v1/roles/${encodeURIComponent(code)}/permissions`, body),
  deleteRole: (code, version, reason) => mutation('delete', `/v1/roles/${encodeURIComponent(code)}${queryString({ expected_version: version })}`, { reason }),
  audits: params => request({ url: `/v1/audit-logs${queryString(params)}` }),
  audit: id => request({ url: `/v1/audit-logs/${encodeURIComponent(id)}` }),
  auditCsv: params => download(`/v1/audit-logs/export.csv${queryString(params)}`, `audit-logs-${new Date().toISOString().slice(0, 10)}.csv`)
};
