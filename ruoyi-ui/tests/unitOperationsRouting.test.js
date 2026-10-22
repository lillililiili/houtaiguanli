import { afterEach, describe, expect, it, vi } from 'vitest'
import { request, mutation, download } from '@/services/apiClient'
import { setManagementContext } from '@/services/managementContext'

afterEach(() => { setManagementContext(null); vi.restoreAllMocks(); vi.unstubAllGlobals() })
const adapter = vi.fn(async config => ({ config, headers: {}, status: 200, statusText: 'OK', data: config.responseType === 'blob' ? new Blob(['report']) : { ok: true, data: {} } }))
describe('单位运维接口分流', () => {
  it.each([
    '/v1/devices?page=2', '/v1/devices/dev%2F1', '/v1/devices/overview', '/v1/device-protocols',
    '/v1/device-monitor/devices/d1/trends?range=1h', '/v1/device-maintenance-tasks/t1/workflow',
    '/v1/commission-tasks/c1/report', '/v1/stats/reports/preview?source_mode=live'
  ])('单位请求%s走受限接口，平台会话仍用原接口', async url => {
    setManagementContext({ mode: 'UNIT', org_id: 'org-a' })
    await request({ url, adapter })
    expect(adapter.mock.lastCall[0].url).toBe(url.replace('/v1/', '/v1/unit-admin/operations/'))
    setManagementContext({ mode: 'PLATFORM' })
    await request({ url, adapter })
    expect(adapter.mock.lastCall[0].url).toBe(url)
  })
  it('范围选项使用本单位目录，平台管理与业务API不被误改写', async () => {
    setManagementContext({ mode: 'UNIT', org_id: 'org-a' })
    await request({ url: '/v1/mqtt-brokers/scopes', adapter })
    expect(adapter.mock.lastCall[0].url).toBe('/v1/unit-admin/operations/scopes')
    for (const url of ['/v1/users', '/v1/roles', '/v1/maps', '/v1/disposal-authorizations', '/v1/sensing/targets', '/v1/unit-admin/users']) {
      await request({ url, adapter }); expect(adapter.mock.lastCall[0].url).toBe(url)
    }
  })
  it('单位写请求保持原方法、请求体和幂等键，不增加前台操作授权', async () => {
    setManagementContext({ mode: 'UNIT', org_id: 'org-b' })
    const body = { device_id: 'own-device', version: 7 }
    await mutation('post', '/v1/commission-tasks', body, { adapter, idempotencyKey: 'same-click-key' })
    const config = adapter.mock.lastCall[0]
    expect(config.url).toBe('/v1/unit-admin/operations/commission-tasks')
    expect(config.method).toBe('post')
    expect(JSON.parse(config.data)).toEqual(body)
    expect(config.headers['Idempotency-Key']).toBe('same-click-key')
    expect(config.headers['X-Client-Type']).toBe('BACKEND')
  })
  it('PDF下载同样使用本单位接口，退出后不沿用单位路由', async () => {
    setManagementContext({ mode: 'UNIT', org_id: 'org-b' })
    const BrowserURL = URL
    vi.stubGlobal('URL', class extends BrowserURL { static createObjectURL = vi.fn(() => 'blob:test'); static revokeObjectURL = vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await download('/v1/stats/reports/export.pdf?period_type=DAILY', '本单位.pdf', { adapter })
    expect(adapter.mock.lastCall[0].url).toBe('/v1/unit-admin/operations/stats/reports/export.pdf?period_type=DAILY')
    setManagementContext(null)
    await request({ url: '/v1/devices', adapter })
    expect(adapter.mock.lastCall[0].url).toBe('/v1/devices')
  })
})
