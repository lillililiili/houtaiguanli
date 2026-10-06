import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive } from 'vue'
import ElementPlus, { ElMessage } from 'element-plus'
import ProfileView from '@/views/profile/ProfileView.vue'
import ChangePasswordForm from '@/components/ChangePasswordForm.vue'
import SessionExpiredDialog from '@/components/SessionExpiredDialog.vue'

// ZT-28 个人资料与主动改密、ZT-29 登录过期就地重新登录的页面行为。
const mocks = vi.hoisted(() => ({ route: null, router: { replace: vi.fn() }, auth: null }))
vi.mock('vue-router', () => ({ useRoute: () => mocks.route, useRouter: () => mocks.router }))
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

const baseUser = { user_id: 'u-1', account: 'duty-1', name: '值班员甲', phone: '13800000001', role_name: '值班员', org_name: '东营市公安局', data_scope: 'OWN_ORG', version: 3, menu_keys: ['devices'], permission_codes: ['devices.read'], must_change_password: false }
let app, host
async function settle() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick() } }
async function mount(component) { host = document.createElement('div'); document.body.append(host); app = createApp(component); app.use(ElementPlus); app.mount(host); await settle() }
const button = label => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === label)
const field = label => [...document.querySelectorAll('.el-form-item')].find(node => node.querySelector('.el-form-item__label')?.textContent.trim() === label)
const input = label => field(label).querySelector('input')
async function type(label, value) { const node = input(label); node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); await settle() }
function apiError(status, code, message) { return Object.assign(new Error(message), { status, code }) }
const errorOf = label => field(label).querySelector('.el-form-item__error')?.textContent.trim() || ''
// 表单项的错误提示有约 100ms 的防抖，机器繁忙时更久，所以轮询等待而不是固定等一段时间。
async function errorShown(label, text) { await vi.waitFor(() => expect(errorOf(label)).toContain(text), { timeout: 5000, interval: 50 }) }

beforeEach(() => {
  vi.clearAllMocks()
  mocks.route = reactive({ query: {}, fullPath: '/operations/devices', meta: { menuKey: 'devices', title: '设备管理' } })
  mocks.auth = reactive({
    user: { ...baseUser }, sessionExpired: false, expiredWhileSubmitting: false, reloginHosts: 0,
    updateProfile: vi.fn(), loadCurrentUser: vi.fn(), changePassword: vi.fn(), relogin: vi.fn(), clear: vi.fn()
  })
})
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

// 挂载 Element Plus 表单较重，机器繁忙时放宽单条超时。
describe('个人资料', { timeout: 20_000 }, () => {
  it('姓名和电话可改，账号、角色、单位、数据范围只读', async () => {
    await mount(ProfileView)
    expect(input('姓名').value).toBe('值班员甲')
    expect(input('姓名').disabled).toBe(false)
    expect(input('联系电话').disabled).toBe(false)
    for (const label of ['账号', '角色', '所属单位', '数据范围']) expect(input(label).disabled).toBe(true)
    expect(input('数据范围').value).toBe('本单位')
    expect(button('保存').disabled).toBe(true)
  })

  it('保存去掉首尾空格，成功后提示', async () => {
    mocks.auth.updateProfile.mockImplementation(async body => { mocks.auth.user = { ...mocks.auth.user, ...body, version: 4 }; return mocks.auth.user })
    await mount(ProfileView)
    await type('姓名', ' 值班员乙 ')
    await type('联系电话', '0546-1234567')
    button('保存').click(); await settle()
    expect(mocks.auth.updateProfile).toHaveBeenCalledWith({ name: '值班员乙', phone: '0546-1234567' })
    expect(ElMessage.success).toHaveBeenCalledWith('个人资料已保存')
    expect(input('姓名').value).toBe('值班员乙')
  })

  it('别处改过资料时读取最新版本，已填内容保留', async () => {
    mocks.auth.updateProfile.mockRejectedValue(apiError(409, 'VERSION_CONFLICT', '资料已被其他操作修改，请刷新后重试'))
    mocks.auth.loadCurrentUser.mockImplementation(async () => { mocks.auth.user = { ...mocks.auth.user, version: 5 }; return mocks.auth.user })
    await mount(ProfileView)
    await type('姓名', '值班员丙')
    button('保存').click(); await settle()
    expect(mocks.auth.loadCurrentUser).toHaveBeenCalled()
    expect(document.body.textContent).toContain('你填写的内容还在')
    expect(input('姓名').value).toBe('值班员丙')
  })

  it('登录过期导致保存失败时不清空填写内容，也不在页面上重复报错', async () => {
    mocks.auth.updateProfile.mockRejectedValue(apiError(401, 'UNAUTHENTICATED', '登录已过期，刚才的操作没有完成。请重新登录后再试。'))
    await mount(ProfileView)
    await type('姓名', '值班员丁')
    button('保存').click(); await settle()
    expect(input('姓名').value).toBe('值班员丁')
    expect(document.querySelector('.profile-form .el-alert')).toBeNull()
  })

  it('从“修改密码”入口进入时直接显示改密表单', async () => {
    mocks.route.query = { section: 'password' }
    await mount(ProfileView)
    const active = document.querySelector('.el-tabs__item.is-active')
    expect(active.textContent.trim()).toBe('修改密码')
    expect(field('当前密码')).toBeTruthy()
  })
})

describe('主动改密', { timeout: 20_000 }, () => {
  async function fill(current, next) {
    await type('当前密码', current)
    await type('新密码', next)
    await type('确认新密码', next)
  }

  it('当前密码输错标在输入框下，留在本页不退出登录', async () => {
    mocks.auth.changePassword.mockRejectedValue(apiError(400, 'CURRENT_PASSWORD_INCORRECT', '当前密码不正确，请重新输入'))
    await mount(ChangePasswordForm)
    await fill('Wrong#2026a', 'Duty#2026Next')
    button('确认修改').click(); await settle()
    await errorShown('当前密码', '当前密码不正确')
    expect(mocks.router.replace).not.toHaveBeenCalled()
    expect(mocks.auth.clear).not.toHaveBeenCalled()
  })

  it('连续输错被锁定时在表单顶部说明', async () => {
    mocks.auth.changePassword.mockRejectedValue(apiError(429, 'PASSWORD_ATTEMPTS_LOCKED', '当前密码连续输错次数过多，请30分钟后再试；这段时间内也不能重新登录'))
    await mount(ChangePasswordForm)
    await fill('Wrong#2026a', 'Duty#2026Next')
    button('确认修改').click(); await settle()
    expect(document.querySelector('.el-alert').textContent).toContain('30分钟后再试')
    expect(mocks.router.replace).not.toHaveBeenCalled()
  })

  it('新密码不合要求时不提交', async () => {
    await mount(ChangePasswordForm)
    await fill('Duty#2026a', 'duty-1-Abc#')
    button('确认修改').click(); await settle()
    await errorShown('新密码', '不能包含登录账号')
    expect(mocks.auth.changePassword).not.toHaveBeenCalled()
  })

  it('修改成功后回登录页用新密码登录', async () => {
    mocks.auth.changePassword.mockResolvedValue(undefined)
    await mount(ChangePasswordForm)
    await fill('Duty#2026a', 'Duty#2026Next')
    button('确认修改').click(); await settle()
    expect(mocks.auth.changePassword).toHaveBeenCalledWith('Duty#2026a', 'Duty#2026Next')
    expect(mocks.router.replace).toHaveBeenCalledWith('/login')
  })
})

describe('登录过期就地重新登录', { timeout: 20_000 }, () => {
  it('弹窗在场时登记，提交被拒时说明没有保存、内容还在', async () => {
    await mount(SessionExpiredDialog)
    expect(mocks.auth.reloginHosts).toBe(1)
    mocks.auth.sessionExpired = true
    mocks.auth.expiredWhileSubmitting = true
    await settle()
    expect(document.body.textContent).toContain('登录已过期')
    expect(document.body.textContent).toContain('刚才的提交没有保存')
    expect(document.body.textContent).toContain('已填写的内容都还在')
    expect(input('账号').value).toBe('duty-1')
    expect(input('账号').disabled).toBe(true)
    app.unmount(); app = null
    expect(mocks.auth.reloginHosts).toBe(0)
  })

  it('重新登录成功后留在原页面', async () => {
    mocks.auth.relogin.mockImplementation(async () => { mocks.auth.sessionExpired = false; return { user: mocks.auth.user, sameUser: true } })
    mocks.auth.sessionExpired = true
    mocks.auth.expiredWhileSubmitting = true
    await mount(SessionExpiredDialog)
    await type('密码', 'Duty#2026a')
    button('重新登录').click(); await settle()
    expect(mocks.auth.relogin).toHaveBeenCalledWith('Duty#2026a')
    expect(mocks.router.replace).not.toHaveBeenCalled()
    expect(ElMessage.success).toHaveBeenCalledWith('已重新登录，请再提交一次刚才的内容。')
  })

  it('密码不对时留在弹窗里提示', async () => {
    mocks.auth.relogin.mockRejectedValue(apiError(401, 'INVALID_CREDENTIALS', '账号或密码错误'))
    mocks.auth.sessionExpired = true
    await mount(SessionExpiredDialog)
    await type('密码', 'wrong')
    button('重新登录').click(); await settle()
    await errorShown('密码', '账号或密码错误')
    expect(mocks.router.replace).not.toHaveBeenCalled()
  })

  it('重新登录后已无本页权限时转到无权访问页', async () => {
    mocks.auth.relogin.mockResolvedValue({ user: { ...baseUser, menu_keys: [] }, sameUser: true })
    mocks.auth.sessionExpired = true
    await mount(SessionExpiredDialog)
    await type('密码', 'Duty#2026a')
    button('重新登录').click(); await settle()
    expect(mocks.router.replace).toHaveBeenCalledWith({ path: '/forbidden', query: { page: '设备管理' } })
  })

  it('换个账号登录时清掉会话回登录页', async () => {
    mocks.auth.sessionExpired = true
    await mount(SessionExpiredDialog)
    button('换个账号登录').click(); await settle()
    expect(mocks.auth.clear).toHaveBeenCalled()
    expect(mocks.router.replace).toHaveBeenCalledWith({ path: '/login', query: { redirect: '/operations/devices' } })
  })
})
