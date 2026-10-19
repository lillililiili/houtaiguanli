import axios from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

const originalAdapter = axios.defaults.adapter
afterEach(() => { axios.defaults.adapter = originalAdapter; vi.resetModules() })

describe('请求超时', () => {
  it.each(['ECONNABORTED', 'ETIMEDOUT'])('将 %s 与网络断开区分，写操作仍提示先核对结果', async code => {
    vi.resetModules()
    axios.defaults.adapter = async config => { throw Object.assign(new Error('timeout'), { code, config }) }
    const { request, mutation, isUncertainOutcome } = await import('@/services/apiClient.js')
    await expect(request({ url: '/v1/stats/reports/preview' })).rejects.toMatchObject({ code: 'TIMEOUT', message: expect.stringContaining('缩短统计周期') })
    const error = await mutation('post', '/v1/example', {}).catch(value => value)
    expect(error.code).toBe('TIMEOUT')
    expect(error.message).toContain('操作结果尚未确认')
    expect(isUncertainOutcome(error)).toBe(true)
  })
  it('报表预览采用统计请求时限，普通查询保持原时限，断网仍如实报错', async () => {
    vi.resetModules()
    const adapter = vi.fn(async config => ({ data: { ok: true, data: {} }, status: 200, headers: {}, config }))
    axios.defaults.adapter = adapter
    const { businessReportApi } = await import('@/api/reports.js')
    const { request } = await import('@/services/apiClient.js')
    await businessReportApi.preview({ source_mode: 'simulated' })
    expect(adapter.mock.calls[0][0].timeout).toBe(120000)
    await request({ url: '/v1/example' })
    expect(adapter.mock.calls[1][0].timeout).toBe(15000)
    adapter.mockImplementation(async config => { throw Object.assign(new Error('offline'), { code: 'ERR_NETWORK', config }) })
    await expect(request({ url: '/v1/example' })).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })
})
