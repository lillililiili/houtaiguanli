import axios from 'axios'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// ZT-29：会话过期时就地重新登录，页面和已填内容保留；ZT-28：改密输错当前密码不能当成会话失效。
const originalAdapter = axios.defaults.adapter
const user = { user_id: 'u-1', account: 'duty-1', name: '值班员', menu_keys: ['devices'], permission_codes: ['devices.read'], must_change_password: false, version: 3 }

function failWith(status, code, message) {
  return async config => {
    const error = new Error(message)
    error.config = config
    error.response = { status, data: { ok: false, error: { code, message } }, headers: {}, config }
    throw error
  }
}

async function loadClient(adapter) {
  vi.resetModules()
  axios.defaults.adapter = adapter
  return import('@/services/apiClient.js')
}

describe('会话失效信号', () => {
  let events
  const record = event => events.push(event.detail)
  beforeEach(() => { events = []; window.addEventListener('admin:unauthorized', record) })
  afterEach(() => { window.removeEventListener('admin:unauthorized', record); axios.defaults.adapter = originalAdapter; vi.resetModules() })

  it('提交类请求被 401 拒绝时标明这次提交没有保存，查询类不标', async () => {
    const { mutation, request } = await loadClient(failWith(401, 'UNAUTHENTICATED', '未登录或会话已失效'))
    await expect(mutation('post', '/v1/alarms/a-1/verify', { result: 'FALSE_ALARM' })).rejects.toMatchObject({ status: 401, message: '登录已过期，刚才的操作没有完成。请重新登录后再试。' })
    await expect(request({ url: '/v1/alarms' })).rejects.toMatchObject({ status: 401 })
    expect(events).toEqual([{ submitting: true }, { submitting: false }])
  })

  it('登录、退出返回 401 和改密输错当前密码都不算会话过期', async () => {
    let client = await loadClient(failWith(401, 'INVALID_CREDENTIALS', '账号或密码错误'))
    await expect(client.request({ method: 'post', url: '/v1/auth/login', data: {} })).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })
    await expect(client.request({ method: 'post', url: '/v1/auth/logout' })).rejects.toMatchObject({ status: 401 })
    client = await loadClient(failWith(400, 'CURRENT_PASSWORD_INCORRECT', '当前密码不正确，请重新输入'))
    await expect(client.request({ method: 'post', url: '/v1/auth/change-password', data: {} })).rejects.toMatchObject({ status: 400, code: 'CURRENT_PASSWORD_INCORRECT' })
    expect(events).toEqual([])
  })
})

describe('就地重新登录', () => {
  let authApi, useAuthStore, writeToken, readToken
  beforeEach(async () => {
    vi.resetModules()
    vi.doMock('@/api/auth.js', () => ({ authApi: { login: vi.fn(), me: vi.fn(), logout: vi.fn(), changePassword: vi.fn(), updateProfile: vi.fn() } }))
    ;({ authApi } = await import('@/api/auth.js'))
    ;({ useAuthStore } = await import('@/stores/auth.js'))
    ;({ writeToken, readToken } = await import('@/services/apiClient.js'))
    sessionStorage.clear()
    setActivePinia(createPinia())
  })
  afterEach(() => { vi.doUnmock('@/api/auth.js'); vi.resetModules() })

  it('用同一账号重新登录后换上新会话，过期标记清除并通知实时连接', async () => {
    writeToken('expired-session')
    const auth = useAuthStore()
    auth.user = user
    auth.markSessionExpired({ submitting: true })
    auth.markSessionExpired({ submitting: false })
    expect(auth.sessionExpired).toBe(true)
    expect(auth.expiredWhileSubmitting).toBe(true)
    authApi.login.mockResolvedValue({ session_id: 'fresh-session', user_id: 'u-1' })
    authApi.me.mockResolvedValue({ ...user, permission_version: 2 })
    const restored = vi.fn()
    window.addEventListener('admin:session-restored', restored, { once: true })
    const result = await auth.relogin('Duty#2026a')
    expect(authApi.login).toHaveBeenCalledWith({ account: 'duty-1', password: 'Duty#2026a' })
    expect(result.sameUser).toBe(true)
    expect(readToken()).toBe('fresh-session')
    expect(auth.sessionExpired).toBe(false)
    expect(auth.expiredWhileSubmitting).toBe(false)
    expect(restored).toHaveBeenCalledTimes(1)
  })

  it('密码输错时保持过期状态，页面不被清空', async () => {
    writeToken('expired-session')
    const auth = useAuthStore()
    auth.user = user
    auth.markSessionExpired({ submitting: true })
    authApi.login.mockRejectedValue(Object.assign(new Error('账号或密码错误'), { status: 401, code: 'INVALID_CREDENTIALS' }))
    await expect(auth.relogin('wrong')).rejects.toThrow('账号或密码错误')
    expect(auth.sessionExpired).toBe(true)
    expect(auth.user).toEqual(user)
    expect(authApi.me).not.toHaveBeenCalled()
  })

  it('本人改资料带上当前版本号，返回后刷新当前用户', async () => {
    const auth = useAuthStore()
    auth.user = user
    authApi.updateProfile.mockResolvedValue({ ...user, name: '新名字', phone: '0546-1234567', version: 4 })
    await auth.updateProfile({ name: '新名字', phone: '0546-1234567' })
    expect(authApi.updateProfile).toHaveBeenCalledWith({ name: '新名字', phone: '0546-1234567', expected_version: 3 })
    expect(auth.user.version).toBe(4)
  })
})

// 首次加载路由模块要编译整套页面配置，机器繁忙时较慢，放宽超时。
describe('路由对会话过期的处理', { timeout: 30_000 }, () => {
  // 路由模块在 window 上注册监听，整个分组只加载一次，免得重复加载留下多份监听互相干扰。
  let router, auth, authApi, useAuthStore
  beforeAll(async () => {
    vi.resetModules()
    vi.doMock('@/api/auth.js', () => ({ authApi: { login: vi.fn(), me: vi.fn(), logout: vi.fn(), changePassword: vi.fn(), updateProfile: vi.fn() } }))
    ;({ authApi } = await import('@/api/auth.js'))
    router = (await import('@/router/index.js')).default
    ;({ useAuthStore } = await import('@/stores/auth.js'))
  }, 60_000)
  beforeEach(() => {
    Object.values(authApi).forEach(fn => fn.mockReset())
    sessionStorage.clear()
    setActivePinia(createPinia())
    auth = useAuthStore()
  })
  afterAll(() => { vi.doUnmock('@/api/auth.js'); vi.resetModules() })

  // 真实请求被 401 拒绝时，apiClient 先发出会话失效信号，再把错误抛给调用方。
  function meExpires() {
    authApi.me.mockImplementation(async () => {
      window.dispatchEvent(new CustomEvent('admin:unauthorized', { detail: { submitting: false } }))
      throw Object.assign(new Error('未登录或会话已失效'), { status: 401 })
    })
  }
  async function signedInAt(path, signedInUser = user) {
    sessionStorage.setItem('uav.admin.session.v1', 'live-session')
    auth.token = 'live-session'
    auth.user = signedInUser
    authApi.me.mockResolvedValue(signedInUser)
    if (router.currentRoute.value.path !== path) await router.push(path)
    expect(router.currentRoute.value.path).toBe(path)
  }

  // 本条必须第一个跑：路由还停在起始位置，模拟刚打开页面就发现会话过期。
  it('打开页面时发现会话过期：带着要去的地址回登录页并说明登录已过期', async () => {
    sessionStorage.setItem('uav.admin.session.v1', 'expired-session')
    auth.token = 'expired-session'
    meExpires()
    await router.push('/profile')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(router.currentRoute.value.query).toMatchObject({ redirect: '/profile', expired: '1' })
    expect(auth.token).toBe('')
  })

  it('后台页面挂着重新登录弹窗时只标记过期，不清会话也不跳登录页', async () => {
    await signedInAt('/profile')
    auth.reloginHosts = 1
    window.dispatchEvent(new CustomEvent('admin:unauthorized', { detail: { submitting: true } }))
    expect(auth.sessionExpired).toBe(true)
    expect(auth.expiredWhileSubmitting).toBe(true)
    expect(auth.token).toBe('live-session')
    // 弹窗开着时不切换页面，已填内容不会随页面卸载丢失。
    const failure = await router.push('/forbidden')
    expect(failure).toBeTruthy()
    expect(router.currentRoute.value.path).toBe('/profile')
  })

  it('后台页面里切换页面时才发现过期：留在当前页，由弹窗提示重新登录', async () => {
    await signedInAt('/profile')
    auth.reloginHosts = 1
    meExpires()
    const failure = await router.push('/forbidden')
    expect(failure).toBeTruthy()
    expect(router.currentRoute.value.path).toBe('/profile')
    expect(auth.sessionExpired).toBe(true)
    expect(auth.expiredWhileSubmitting).toBe(false)
    expect(auth.user.account).toBe('duty-1')
    expect(auth.token).toBe('live-session')
  })

  it('首次改密页没有重新登录弹窗：回登录页并说明登录已过期', async () => {
    await signedInAt('/change-password', { ...user, must_change_password: true })
    window.dispatchEvent(new CustomEvent('admin:unauthorized', { detail: { submitting: true } }))
    expect(auth.user).toBeNull()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(router.currentRoute.value.query).toMatchObject({ redirect: '/change-password', expired: '1' })
  })

  it('没有重新登录弹窗时切换页面发现过期，同样回登录页并提示', async () => {
    await signedInAt('/profile')
    authApi.me.mockRejectedValue(Object.assign(new Error('未登录或会话已失效'), { status: 401 }))
    await router.push('/forbidden')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query).toMatchObject({ redirect: '/forbidden', expired: '1' })
  })

  it('非强制改密时旧改密地址转到个人资料的修改密码', async () => {
    sessionStorage.setItem('uav.admin.session.v1', 'live-session')
    auth.token = 'live-session'
    authApi.me.mockResolvedValue(user)
    await router.push('/change-password')
    expect(router.currentRoute.value.path).toBe('/profile')
    expect(router.currentRoute.value.query.section).toBe('password')
  })
})
