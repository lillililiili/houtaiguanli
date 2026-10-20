import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import UsersView from '@/views/system/UsersView.vue'
import AuditView from '@/views/system/AuditView.vue'
import { systemApi } from '@/api/system'

vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({
  user: { user_type: 'BACKEND', menu_keys: ['users'], permission_codes: ['users.read'] },
  hasPermission: () => true
}) }))
vi.mock('@/api/system', () => ({ systemApi: {
  users: vi.fn(), roles: vi.fn(), organizations: vi.fn(), audits: vi.fn(), auditCsv: vi.fn()
} }))
vi.mock('@/views/system/organization/OrganizationDialog.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/views/system/DistrictsDialog.vue', () => ({ default: { template: '<div />' } }))

const orgs = [{ org_id: 'org-north', name: '北区单位' }, { org_id: 'org-south', name: '南区单位' }]
let app, host, pending
function deferred() {
  let resolve, reject
  const promise = new Promise((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
async function settle() {
  for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() }
  // Let Element Plus complete loading-mask and layout transitions before reading the UI.
  await vi.advanceTimersByTimeAsync(350)
  await nextTick()
}
async function mount(component) {
  host = document.createElement('div'); document.body.append(host)
  app = createApp(component); app.use(ElementPlus); app.mount(host)
  await settle()
}
async function clickButton(label) {
  const button = [...host.querySelectorAll('button')].find(node => node.textContent.trim() === label)
  expect(button, `缺少按钮 ${label}`).toBeTruthy()
  button.click(); await settle()
}
function tableText() { return host.querySelector('.el-table__body').textContent }
function totalText() { return host.querySelector('.el-pagination__total').textContent }
function isLoading() { return [...host.querySelectorAll('.el-table .el-loading-mask')].some(node => node.style.display !== 'none') }
function result(account, total = 1) {
  return { items: [{ user_id: `user-${account}`, audit_id: `audit-${account}`, account, name: account,
    status: 'ACTIVE', result: 'SUCCESS', occurred_at: 123456, user_type: 'BACKEND' }], total }
}
async function resolve(request, account, total = 1) { request.resolve(result(account, total)); await settle() }
async function selectOrg(org) {
  const label = [...host.querySelectorAll('.org-node__label')].find(node => node.textContent.trim() === org.name)
  expect(label).toBeTruthy(); label.click(); await settle()
}
async function query(placeholder, value) {
  const input = host.querySelector(`input[placeholder="${placeholder}"]`)
  input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  await clickButton('查询')
}

beforeEach(() => {
  vi.useFakeTimers(); vi.resetAllMocks(); pending = []
  systemApi.roles.mockResolvedValue([])
  systemApi.organizations.mockResolvedValue(orgs)
  systemApi.auditCsv.mockResolvedValue()
  for (const api of [systemApi.users, systemApi.audits]) api.mockImplementation(params => {
    const request = { ...deferred(), params }; pending.push(request); return request.promise
  })
})
afterEach(async () => {
  app?.unmount(); app = undefined
  await vi.runOnlyPendingTimersAsync()
  host?.remove(); document.body.innerHTML = ''; vi.useRealTimers()
})

describe.each([
  { title: '用户列表', component: UsersView, placeholder: '账号、姓名或联系电话', key: 'keyword' },
  { title: '审计日志', component: AuditView, placeholder: '登录账号', key: 'account' }
])('$title 的请求时序', ({ component, placeholder, key }) => {
  it.each([['north', 'south'], ['south', 'north']])('只展示最后一次查询 %s → %s 的记录和总数', async (first, last) => {
    await mount(component); await resolve(pending[0], 'initial')
    await query(placeholder, first); const old = pending.at(-1)
    await query(placeholder, last); const latest = pending.at(-1)
    expect(old.params[key]).toBe(first); expect(latest.params[key]).toBe(last)
    await resolve(latest, `latest-${last}`, 37)
    await resolve(old, `stale-${first}`, 83)
    expect(tableText()).toContain(`latest-${last}`); expect(tableText()).not.toContain(`stale-${first}`)
    expect(totalText()).toContain('37'); expect(isLoading()).toBe(false)
    if (component === AuditView) {
      await clickButton('导出 CSV')
      expect(systemApi.auditCsv).toHaveBeenLastCalledWith(expect.objectContaining({ account: last }))
    }
  })

  it.each(['resolve', 'reject'])('旧请求 %s 不能提前结束新请求的加载或显示旧错误', async outcome => {
    await mount(component); await resolve(pending[0], 'initial', 17)
    await query(placeholder, 'slow-old'); const old = pending.at(-1)
    await query(placeholder, 'still-loading'); const latest = pending.at(-1)
    if (outcome === 'resolve') old.resolve(result('stale', 95))
    else old.reject(new Error('旧查询失败'))
    await settle()
    expect(isLoading()).toBe(true); expect(host.textContent).not.toContain('旧查询失败')
    expect(tableText()).toContain('initial'); expect(totalText()).toContain('17')
    await resolve(latest, 'current', 23)
    expect(isLoading()).toBe(false); expect(tableText()).toContain('current'); expect(totalText()).toContain('23')
  })

  it('较晚到达的旧失败不能污染已经成功的新查询', async () => {
    await mount(component); await resolve(pending[0], 'initial')
    await query(placeholder, 'old'); const old = pending.at(-1)
    await query(placeholder, 'new'); await resolve(pending.at(-1), 'current', 29)
    old.reject(new Error('过期请求失败')); await settle()
    expect(host.textContent).not.toContain('过期请求失败')
    expect(tableText()).toContain('current'); expect(totalText()).toContain('29'); expect(isLoading()).toBe(false)
  })

  it('最新查询为空时保持空结果和零条，不能被旧数据重新填充', async () => {
    await mount(component); await resolve(pending[0], 'initial', 17)
    await query(placeholder, 'old'); const old = pending.at(-1)
    await query(placeholder, 'no-match'); pending.at(-1).resolve({ items: [], total: 0 }); await settle()
    await resolve(old, 'stale', 93)
    expect(host.querySelectorAll('.el-table__body tr')).toHaveLength(0)
    expect(totalText()).toMatch(/\b0\b/); expect(isLoading()).toBe(false)
    expect(host.querySelector('.el-table__empty-text').textContent).toContain('当前条件下暂无')
  })

  it.each(['resolve', 'reject'])('最新查询失败后保留错误，旧请求 %s 不能改写结果，重新查询可恢复', async outcome => {
    await mount(component); await resolve(pending[0], 'last-success', 19)
    await query(placeholder, 'old'); const old = pending.at(-1)
    await query(placeholder, 'new'); pending.at(-1).reject(new Error('最新查询暂不可用')); await settle()
    expect(host.textContent).toContain('最新查询暂不可用'); expect(isLoading()).toBe(false)
    if (outcome === 'resolve') old.resolve(result('stale', 87))
    else old.reject(new Error('过期错误'))
    await settle()
    expect(host.textContent).toContain('最新查询暂不可用'); expect(host.textContent).not.toContain('过期错误')
    expect(tableText()).toContain('last-success'); expect(tableText()).not.toContain('stale'); expect(totalText()).toContain('19')
    await clickButton('查询')
    expect(host.textContent).not.toContain('最新查询暂不可用'); expect(isLoading()).toBe(true)
    await resolve(pending.at(-1), 'recovered', 31)
    expect(tableText()).toContain('recovered'); expect(totalText()).toContain('31'); expect(isLoading()).toBe(false)
  })

  it('翻页请求未结束时重置条件，只展示清空条件后的第一页', async () => {
    await mount(component); await resolve(pending[0], 'initial', 70)
    await query(placeholder, 'filtered'); await resolve(pending.at(-1), 'filtered', 70)
    const nextPage = [...host.querySelectorAll('.el-pager li')].find(node => node.textContent.trim() === '2')
    expect(nextPage).toBeTruthy(); nextPage.click(); await settle(); const old = pending.at(-1)
    expect(old.params).toMatchObject({ [key]: 'filtered', page: 2 })
    await clickButton('重置'); const latest = pending.at(-1)
    expect(latest.params).toMatchObject({ [key]: '', page: 1 })
    await resolve(latest, 'unfiltered-first-page', 66); await resolve(old, 'old-second-page', 45)
    expect(tableText()).toContain('unfiltered-first-page'); expect(tableText()).not.toContain('old-second-page')
    expect(totalText()).toContain('66'); expect(host.querySelector('.el-pager .is-active').textContent.trim()).toBe('1')
  })

  it.each(['resolve', 'reject'])('页面卸载后不再消费请求的 %s 结果', async outcome => {
    await mount(component); const old = pending.at(-1)
    app.unmount(); app = undefined
    const read = vi.fn()
    if (outcome === 'resolve') old.resolve({ get items() { read(); return [] }, get total() { read(); return 0 } })
    else old.reject({ get message() { read(); return '已离开页面' } })
    await settle(); expect(read).not.toHaveBeenCalled()
  })
})

describe('用户单位树与目录刷新', () => {
  it.each([[0, 1], [1, 0]])('切换单位 %s → %s 时旧列表不能覆盖当前单位', async (first, last) => {
    await mount(UsersView); await resolve(pending[0], 'initial')
    await selectOrg(orgs[first]); const old = pending.at(-1)
    await selectOrg(orgs[last]); const latest = pending.at(-1)
    expect(old.params.orgId).toBe(orgs[first].org_id); expect(latest.params.orgId).toBe(orgs[last].org_id)
    await resolve(latest, 'selected-org-user', 24); await resolve(old, 'wrong-org-user', 41)
    expect(host.querySelector('.table-toolbar').textContent).toContain(orgs[last].name)
    expect(tableText()).toContain('selected-org-user'); expect(tableText()).not.toContain('wrong-org-user')
    expect(totalText()).toContain('24')
  })

  it('旧目录错误不能覆盖刷新期间发起的新单位查询或结束其加载', async () => {
    await mount(UsersView); await resolve(pending[0], 'initial')
    const catalog = deferred(); systemApi.organizations.mockReturnValueOnce(catalog.promise)
    await clickButton('刷新'); await selectOrg(orgs[1]); const latest = pending.at(-1)
    catalog.reject(new Error('旧目录失败')); await settle()
    expect(host.textContent).not.toContain('旧目录失败'); expect(isLoading()).toBe(true)
    await resolve(latest, 'current-unit-user', 32)
    expect(tableText()).toContain('current-unit-user'); expect(isLoading()).toBe(false)
  })

  it('两次目录刷新只采用新目录，并按最新单位和筛选读取用户', async () => {
    await mount(UsersView); await resolve(pending[0], 'initial')
    const oldCatalog = deferred(), latestCatalog = deferred()
    systemApi.organizations.mockReturnValueOnce(oldCatalog.promise).mockReturnValueOnce(latestCatalog.promise)
    await clickButton('刷新'); await selectOrg(orgs[1]); await clickButton('刷新')
    const latestOrgs = orgs.map(org => ({ ...org, name: `${org.name}（更新）` }))
    latestCatalog.resolve(latestOrgs); await settle(); const latest = pending.at(-1)
    expect(latest.params.orgId).toBe(orgs[1].org_id)
    await resolve(latest, 'current-user', 28)
    const count = systemApi.users.mock.calls.length
    oldCatalog.resolve([{ org_id: 'retired-org', name: '过期单位' }]); await settle()
    expect(systemApi.users).toHaveBeenCalledTimes(count)
    expect(host.querySelector('.org-panel').textContent).toContain('南区单位（更新）')
    expect(host.querySelector('.org-panel').textContent).not.toContain('过期单位')
    expect(tableText()).toContain('current-user'); expect(totalText()).toContain('28')
  })

  it('最新目录失败会结束加载并允许重新刷新', async () => {
    systemApi.organizations.mockRejectedValueOnce(new Error('目录暂不可用'))
    await mount(UsersView)
    expect(host.textContent).toContain('目录暂不可用'); expect(isLoading()).toBe(false)
    expect(systemApi.users).not.toHaveBeenCalled()
    await clickButton('重新加载'); await resolve(pending.at(-1), 'reloaded', 12)
    expect(host.textContent).not.toContain('目录暂不可用'); expect(tableText()).toContain('reloaded')
  })

  it('离开页面后目录成功不会再发起用户列表请求', async () => {
    const catalog = deferred(); systemApi.organizations.mockReturnValueOnce(catalog.promise)
    await mount(UsersView); app.unmount(); app = undefined
    catalog.resolve(orgs); await settle()
    expect(systemApi.users).not.toHaveBeenCalled()
  })
})
