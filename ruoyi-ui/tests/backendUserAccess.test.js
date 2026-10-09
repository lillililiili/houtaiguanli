import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'
import { readToken, SESSION_KEY } from '@/services/apiClient'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), me: vi.fn() } }))
beforeEach(() => {
  setActivePinia(createPinia())
  sessionStorage.clear()
  vi.clearAllMocks()
  authApi.login.mockResolvedValue({ session_id: 'test-session' })
})

describe('后台账号准入', () => {
  it.each(['FRONTEND', undefined])('用户类型 %s 不得依赖菜单列表进入后台', async user_type => {
    authApi.me.mockResolvedValue({ user_type, menu_keys: ['devices', 'roles'], permission_codes: ['roles.auth'] })
    const auth = useAuthStore()
    await expect(auth.login({ account: 'test', password: 'test-only' })).rejects.toMatchObject({ code: 'BACKEND_ACCESS_DENIED' })
    expect(auth.authenticated).toBe(false)
    expect(readToken()).toBe('')
  })
  it('后台用户可登录并恢复自己的会话', async () => {
    authApi.me.mockResolvedValue({ user_type: 'BACKEND', user_id: 'backend-user', menu_keys: ['users'] })
    const auth = useAuthStore()
    await auth.login({ account: 'operator', password: 'test-only' })
    expect(auth.authenticated).toBe(true)
    expect((await auth.restore()).user_type).toBe('BACKEND')
  })
  it('更新期间只兼容旧后端的超级管理员身份', async () => {
    authApi.me.mockResolvedValue({ role_code: 'ROLE-ADMIN', user_id: 'super-admin' })
    const auth = useAuthStore()
    await auth.login({ account: 'admin', password: 'test-only' })
    expect(auth.authenticated).toBe(true)
  })
  it('粘贴前台会话也不能恢复后台登录', async () => {
    sessionStorage.setItem(SESSION_KEY, 'frontend-session')
    authApi.me.mockRejectedValue(Object.assign(new Error('仅后台用户可进入'), { code: 'BACKEND_ACCESS_DENIED', status: 403 }))
    const auth = useAuthStore()
    expect(await auth.restore()).toBeNull()
    expect(auth.authenticated).toBe(false)
    expect(readToken()).toBe('')
  })
})
