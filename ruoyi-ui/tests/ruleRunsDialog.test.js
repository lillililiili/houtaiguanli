import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref } from 'vue'
import ElementPlus from 'element-plus'
import { automationRuleApi } from '@/api/automationRules.js'
import RuleRunsDialog from '@/views/system/rules/RuleRunsDialog.vue'

vi.mock('@/api/automationRules.js', () => ({ automationRuleApi: { runs: vi.fn() } }))
let app, host, activeCategory
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
async function settle() { for (let index = 0; index < 10; index += 1) { await Promise.resolve(); await nextTick() } }
async function mount(category = 'verify') { host = document.createElement('div'); document.body.append(host); app = createApp({ setup() { activeCategory = ref(category); return () => h(RuleRunsDialog, { modelValue: true, category: activeCategory.value }) } }); app.use(ElementPlus); app.mount(host); await settle() }
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = ''; vi.clearAllMocks() })

describe('规则运行记录弹窗', () => {
  it('完整区分排队、模拟和真实成功动作', async () => {
    automationRuleApi.runs.mockResolvedValue({ items: [{ run_id: 'run-1', event_id: 'event-1', target_id: 'target-1', group_version: 4, status: 'PASS', evaluated_at: 1, observed_at: 1, source_mode: 'replay', conditions: [{ name: '身份关联', result: 'PASS', actual: '已关联', expected: '明确关联', reason: '证据齐全' }], actions: [{ code: 'notify', status: 'QUEUED', reason: '等待发送', reference: 'notice-1' }, { code: 'voice', status: 'SIMULATED', reason: '回放环境' }, { code: 'audit', status: 'SUCCEEDED', reason: '已写入' }] }], page: 1, size: 20, total: 1 })
    await mount()
    expect(document.body.textContent).toContain('已进入执行队列')
    expect(document.body.textContent).toContain('模拟结果')
    expect(document.body.textContent).toContain('执行成功')
    expect(document.body.textContent).toContain('证据齐全')
  })
  it('分类变化后忽略旧分类的过期响应', async () => {
    const verify = deferred(), counter = deferred()
    automationRuleApi.runs.mockImplementation(category => category === 'verify' ? verify.promise : counter.promise)
    await mount('verify')
    activeCategory.value = 'counter'; await settle()
    counter.resolve({ items: [{ run_id: 'counter-run', status: 'PAUSED', conditions: [], actions: [] }], total: 1 }); await settle()
    verify.resolve({ items: [{ run_id: 'verify-run', status: 'PASS', conditions: [], actions: [] }], total: 1 }); await settle()
    expect(document.body.textContent).toContain('counter-run')
    expect(document.body.textContent).not.toContain('verify-run')
  })
  it('读取失败保留异常事实并提供重新读取', async () => { automationRuleApi.runs.mockRejectedValue(new Error('没有运行记录读取权限')); await mount(); expect(document.body.textContent).toContain('没有运行记录读取权限'); expect(document.body.textContent).toContain('重新读取') })
})
