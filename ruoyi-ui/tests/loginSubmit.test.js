import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus, { ElMessage } from 'element-plus'
import LoginView from '@/views/auth/LoginView.vue'

const auth = vi.hoisted(() => ({ login: vi.fn() }))
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => auth }))
vi.mock('@/router/index.js', () => ({ safeRedirect: () => '/home' }))
vi.mock('@/config/navigation.js', () => ({ firstAccessiblePath: () => '/home' }))
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn() } }))

let app, host
async function settle() { for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/login', component: LoginView }, { path: '/home', component: { template: '<p>首页</p>' } }] })
  await router.push('/login'); await router.isReady()
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ template: '<router-view />' }); app.use(router); app.use(ElementPlus); app.mount(host); await settle()
}
function fill(account, password) {
  const [accountInput, passwordInput] = host.querySelectorAll('input.el-input__inner')
  accountInput.value = account; accountInput.dispatchEvent(new Event('input', { bubbles: true }))
  passwordInput.value = password; passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
  return passwordInput
}

beforeEach(() => { vi.clearAllMocks(); localStorage.clear() })
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

describe('后台登录只提交一次', () => {
  it.each(['admin', `ops-${Date.now()}`])('在密码框按回车（浏览器同时触发表单提交）只发一个登录请求、只提示一次：%s', async account => {
    auth.login.mockResolvedValue({ account, must_change_password: false })
    await mount()
    const password = fill(account, 'Secret#123'); await settle()
    password.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    host.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
    expect(auth.login).toHaveBeenCalledTimes(1)
    expect(auth.login).toHaveBeenCalledWith({ account, password: 'Secret#123' })
    expect(ElMessage.success).toHaveBeenCalledTimes(1)
  })

  it('登录请求未返回时重复提交被忽略', async () => {
    let finish
    auth.login.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await mount()
    fill('duty', 'Secret#123'); await settle()
    const form = host.querySelector('form')
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
    expect(auth.login).toHaveBeenCalledTimes(1)
    finish({ account: 'duty', must_change_password: false }); await settle()
    expect(ElMessage.success).toHaveBeenCalledTimes(1)
  })
})
