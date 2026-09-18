import { beforeEach, describe, expect, it, vi } from 'vitest'
import { automationRuleApi } from '@/api/automationRules.js'
import { mutation, request } from '@/services/apiClient.js'

vi.mock('@/services/apiClient.js', () => ({ mutation: vi.fn(), request: vi.fn(), queryString: values => `?page=${values.page}&size=${values.size}`, newIdempotencyKey: vi.fn(prefix => `${prefix}-key`) }))

describe('自动化规则 API', () => {
  beforeEach(() => vi.clearAllMocks())
  it('读取分类组、分页历史和运行记录', () => {
    automationRuleApi.group('verify'); automationRuleApi.history('dispose', { page: 2, size: 20 }); automationRuleApi.runs('counter', { page: 3, size: 20 })
    expect(request).toHaveBeenNthCalledWith(1, { url: '/v1/automation-rule-groups/verify' })
    expect(request).toHaveBeenNthCalledWith(2, { url: '/v1/automation-rule-groups/dispose/history?page=2&size=20' })
    expect(request).toHaveBeenNthCalledWith(3, { url: '/v1/automation-rule-groups/counter/runs?page=3&size=20' })
  })
  it('保存规则时发送明确幂等键并保留字符串 value', () => { const body = { name: '时效要求', item_code: 'freshness', value: '30', hold_seconds: 0, enabled: true, expected_version: 4 }; automationRuleApi.createRule('verify', body); expect(mutation).toHaveBeenCalledWith('post', '/v1/automation-rule-groups/verify/rules', body, { idempotencyKey: 'automation-rule-create-key' }) })
  it('启停仅提交新状态和全组版本', () => { automationRuleApi.setRuleEnabled('counter', 'rule/1', { enabled: false, expected_version: 7 }); expect(mutation).toHaveBeenCalledWith('patch', '/v1/automation-rule-groups/counter/rules/rule%2F1/enabled', { enabled: false, expected_version: 7 }, { idempotencyKey: 'automation-rule-enabled-key' }) })
})
