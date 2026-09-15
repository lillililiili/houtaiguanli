// @vitest-environment node
import axios from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import viteConfig from '../vite.config.js'

vi.mock('vite', async importOriginal => ({ ...await importOriginal(), loadEnv: () => ({}) }))

const originalAdapter = axios.defaults.adapter

afterEach(() => {
  axios.defaults.adapter = originalAdapter
  vi.unstubAllEnvs()
  vi.resetModules()
})

async function captureLogin(base, development) {
  vi.stubEnv('VITE_APP_BASE_API', base)
  vi.stubEnv('DEV', development)
  vi.resetModules()
  const requests = []
  axios.defaults.adapter = async config => {
    requests.push(config)
    return { data: { ok: true, data: {} }, status: 200, headers: {}, config }
  }
  const { authApi } = await import('@/api/auth.js')
  await authApi.login({ username: 'test-user', password: 'test-only' })
  await authApi.me()
  return requests
}

describe('登录请求路由', () => {
  it('未配置环境文件时，登录和会话查询通过开发代理到达新后端', async () => {
    vi.stubEnv('ADMIN_API_PROXY_TARGET', '')
    const requests = await captureLogin('', true)
    const { proxy } = viteConfig({ mode: 'development' }).server
    for (const request of requests) {
      const route = proxy[request.baseURL]
      expect(route, `缺少 ${request.baseURL} 的开发代理`).toBeDefined()
      expect(route.target).toBe('http://127.0.0.1:8081')
      expect(route.rewrite(`${request.baseURL}${request.url}`)).toBe(`/api${request.url}`)
    }
    expect(requests.map(request => request.url)).toEqual(['/v1/auth/login', '/v1/auth/me'])
  })

  it('生产环境默认使用公共 /api 路径', async () => {
    const requests = await captureLogin('', false)
    expect(requests.map(request => request.baseURL)).toEqual(['/api', '/api'])
  })

  it('保留显式配置的 API 地址并去掉尾部斜杠', async () => {
    const requests = await captureLogin('/api/', true)
    expect(requests.map(request => request.baseURL)).toEqual(['/api', '/api'])
  })
})
