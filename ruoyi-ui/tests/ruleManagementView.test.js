import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import RuleManagementView from '@/views/system/rules/RuleManagementView.vue'
import { ruleSetApi } from '@/api/ruleSets'
import { automationRuleApi } from '@/api/automationRules'

const permissions = vi.hoisted(() => ({ codes: new Set() }))
const groupState = vi.hoisted(() => ({ value: null }))
vi.mock('@/api/automationRules', () => ({ automationRuleApi: { runs: vi.fn(), history: vi.fn() } }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: code => permissions.codes.has(code) }) }))
vi.mock('@/api/ruleSets', () => ({ ruleSetApi: { list: vi.fn(), versions: vi.fn(), activate: vi.fn() } }))
vi.mock('@/views/system/rules/useAutomationRuleGroup', async () => {
  const { ref } = await import('vue')
  return { useAutomationRuleGroup: () => ({
    category: ref(groupState.value?.category || 'verify'), group: ref(groupState.value), loading: ref(false), saving: ref(false),
    error: ref(''), actionError: ref(''), uncertain: ref(''), recoveryRevision: ref(0),
    load: vi.fn(), mutate: vi.fn(), clearActionError: vi.fn(),
  }) }
})

let app, host
async function settle() { for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  host = document.createElement('div'); document.body.append(host)
  app = createApp(RuleManagementView); app.use(ElementPlus); app.mount(host); await settle()
}
const opener = () => [...host.querySelectorAll('button')].find(button => button.textContent.trim() === '研判规则版本')

describe('规则管理中的研判规则版本入口', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    permissions.codes = new Set(['rule:read'])
    ruleSetApi.list.mockResolvedValue({ items: [{ rule_set_id: 'set-a', rule_set_code: 'LEGALITY', name: '合法性规则', version: 7 }] })
    ruleSetApi.versions.mockResolvedValue({ items: [{ rule_set_version_id: 'legality-confirmed-independent', version_no: 3, param_status: 'CONFIRMED', status_code: 'PUBLISHED', activation_allowed: true }] })
  })
  afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; groupState.value = null })

  it('没有查看权限时不显示入口，也不读取研判规则接口', async () => {
    permissions.codes = new Set(['rule:manage', 'responsePlans.read'])
    await mount()
    expect(opener()).toBeUndefined()
    expect(ruleSetApi.list).not.toHaveBeenCalled()
    expect(host.querySelector('.rule-set-card')).toBeNull()
  })

  it('默认折叠，首次展开后只有一个组件，折叠重开不重复挂载请求', async () => {
    await mount()
    expect(opener().getAttribute('aria-expanded')).toBe('false')
    expect(ruleSetApi.list).not.toHaveBeenCalled()
    opener().click(); await settle()
    expect(opener().getAttribute('aria-expanded')).toBe('true')
    expect(host.querySelectorAll('.rule-set-card')).toHaveLength(1)
    expect(host.textContent).toContain('legality-confirmed-independent')
    opener().click(); await settle()
    expect(host.querySelector('#judgement-rule-versions').style.display).toBe('none')
    opener().click(); await settle()
    expect(host.querySelectorAll('.rule-set-card')).toHaveLength(1)
    expect(ruleSetApi.list).toHaveBeenCalledTimes(1)
    expect(ruleSetApi.versions).toHaveBeenCalledTimes(1)
  })

  it('仅查看权限可展开查看明确版本，但不能启用', async () => {
    await mount(); opener().click(); await settle()
    expect(host.textContent).toContain('启用需要规则管理权限')
    expect([...host.querySelectorAll('button')].some(button => button.textContent.trim() === '启用此版本')).toBe(false)
    expect(ruleSetApi.activate).not.toHaveBeenCalled()
  })
})

describe('规则管理的运行记录与变更记录入口', () => {
  const group = category => ({ category, version: 5, can_manage: false, execution_status: 'CONNECTED', settings: { scope_mode: 'ALL', schedule_mode: 'ALL_DAY', insufficient_wait_seconds: 15, actions: [] }, rules: [], catalog: [] })
  beforeEach(() => {
    vi.clearAllMocks()
    permissions.codes = new Set(['responsePlans.read'])
    automationRuleApi.runs.mockResolvedValue({ items: [{ run_id: 'run-internal-7', event_id: 'event-internal-7', target_id: 'target-internal-7', group_version: 5, status: 'NOT_MATCHED', evaluated_at: 1_790_000_000_000, conditions: [], actions: [] }], total: 1 })
    automationRuleApi.history.mockResolvedValue({ items: [{ change_id: 'c1', version: 5, action: '新建「核实观测有效」', actor: '管理员', created_at: 1_790_000_000_000, details: ['名称：核实观测有效'] }], total: 1 })
  })
  afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; groupState.value = null })
  const button = text => [...host.querySelectorAll('button')].find(item => item.textContent.trim() === text)

  it.each(['verify', 'counter'])('分类 %s 读到配置后提供两个入口，按当前分类读取记录', async category => {
    groupState.value = group(category)
    await mount()
    button('运行记录').click(); await settle()
    expect(automationRuleApi.runs).toHaveBeenCalledWith(category, { page: 1, size: 20 })
    const runs = document.querySelector('.automation-rule-runs-dialog')
    expect(runs.querySelector('.run-entry > header').textContent).not.toContain('run-internal-7')
    expect(runs.querySelector('.run-entry > header').textContent).not.toContain('target-internal-7')
    expect(runs.querySelector('.tech-details').textContent).toContain('run-internal-7')
    button('变更记录').click(); await settle()
    expect(automationRuleApi.history).toHaveBeenCalledWith(category, { page: 1, size: 20 })
    expect(document.querySelector('.automation-rule-history-dialog').textContent).toContain('新建「核实观测有效」')
  })

  it('还没读到规则配置时不显示入口，也不读取记录', async () => {
    await mount()
    expect(button('运行记录')).toBeUndefined()
    expect(button('变更记录')).toBeUndefined()
    expect(automationRuleApi.runs).not.toHaveBeenCalled()
  })
})

