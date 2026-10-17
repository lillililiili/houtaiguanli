import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import RuleManagementView from '@/views/system/rules/RuleManagementView.vue'
import { ruleSetApi } from '@/api/ruleSets'

const permissions = vi.hoisted(() => ({ codes: new Set() }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: code => permissions.codes.has(code) }) }))
vi.mock('@/api/ruleSets', () => ({ ruleSetApi: { list: vi.fn(), versions: vi.fn(), activate: vi.fn() } }))
vi.mock('@/views/system/rules/useAutomationRuleGroup', async () => {
  const { ref } = await import('vue')
  return { useAutomationRuleGroup: () => ({
    category: ref('verify'), group: ref(null), loading: ref(false), saving: ref(false),
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
  afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

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
