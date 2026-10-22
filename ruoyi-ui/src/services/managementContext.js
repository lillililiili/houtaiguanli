let context = null
export function setManagementContext(value) { context = value || null }
export function currentManagementContext() { return context }
// 仅将已开放的运维资源导向受限接口；不重写平台系统管理或前台业务请求。
export function managementRequestUrl(url) {
  if (context?.mode !== 'UNIT' || typeof url !== 'string') return url
  if (url === '/v1/mqtt-brokers/scopes') return '/v1/unit-admin/operations/scopes'
  const allowed = /^\/v1\/(?:devices(?:\/|\?|$)|device-protocols(?:\?|$)|device-monitor\/|device-events(?:\?|$)|device-incidents(?:\/|\?|$)|device-maintenance-tasks(?:\/|\?|$)|device-maintenance-messages(?:\/|\?|$)|commission-tasks(?:\/|\?|$)|stats\/reports\/)/
  return allowed.test(url) ? url.replace(/^\/v1\//, '/v1/unit-admin/operations/') : url
}
