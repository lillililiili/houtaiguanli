import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ruleSetApi } from '@/api/ruleSets.js'
import { mutation, request } from '@/services/apiClient.js'

vi.mock('@/services/apiClient.js', () => ({ mutation: vi.fn(), request: vi.fn(), queryString: values => `?page=${values.page}&size=${values.size}`, newIdempotencyKey: vi.fn(prefix => `${prefix}-key`) }))

describe('研判规则集 API', () => {
  beforeEach(() => vi.clearAllMocks())
  it('读取规则集和版本时一次取一页 100 条，编码按路径段转义', () => {
    ruleSetApi.list(); ruleSetApi.versions('LEGALITY/DEMO')
    expect(request).toHaveBeenNthCalledWith(1, { url: '/v1/rule-sets?page=1&size=100' })
    expect(request).toHaveBeenNthCalledWith(2, { url: '/v1/rule-sets/LEGALITY%2FDEMO/versions?page=1&size=100' })
  })
  it('启用只提交版本、原因和规则集版本号，并带明确幂等键', () => {
    const body = { rule_set_version_id: 'v1', note: '启用', expected_version: 0 }
    ruleSetApi.activate('LEGALITY-DEMO', body)
    expect(mutation).toHaveBeenCalledWith('post', '/v1/rule-sets/LEGALITY-DEMO/activate', body, { idempotencyKey: 'rule-set-activate-key' })
  })
})
