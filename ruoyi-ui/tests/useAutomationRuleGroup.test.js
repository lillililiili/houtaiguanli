import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { automationRuleApi } from '@/api/automationRules.js'
import { useAutomationRuleGroup } from '@/views/system/rules/useAutomationRuleGroup.js'

vi.mock('@/api/automationRules.js', () => ({ automationRuleApi: { group: vi.fn() } }))
vi.mock('@/services/apiClient.js', () => ({ isUncertainOutcome: error => error?.code === 'NETWORK_ERROR' }))
let app, host, state
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
async function settle() { for (let index = 0; index < 6; index += 1) { await Promise.resolve(); await nextTick() } }
function mount() {
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ setup() { state = useAutomationRuleGroup(); return () => h('div') } }); app.mount(host)
}
afterEach(() => { app?.unmount(); host?.remove(); vi.clearAllMocks() })

describe('规则组并发状态', () => {
  it('忽略分类切换前返回的过期读取', async () => {
    const verify = deferred(), counter = deferred()
    automationRuleApi.group.mockImplementation(category => category === 'verify' ? verify.promise : counter.promise)
    mount(); state.load('verify'); state.load('counter')
    counter.resolve({ category: 'counter', version: 2 }); await settle()
    verify.resolve({ category: 'verify', version: 1 }); await settle()
    expect(state.category.value).toBe('counter')
    expect(state.group.value).toMatchObject({ category: 'counter', version: 2 })
  })

  it('切换分类后不应用旧分类保存响应', async () => {
    automationRuleApi.group.mockResolvedValueOnce({ category: 'verify', version: 1 }).mockResolvedValueOnce({ category: 'counter', version: 4 })
    mount(); await state.load('verify')
    const write = deferred(), saving = state.mutate(() => write.promise)
    await state.load('counter'); write.resolve({ category: 'verify', version: 2 }); await saving; await settle()
    expect(state.group.value).toMatchObject({ category: 'counter', version: 4 })
  })
})
