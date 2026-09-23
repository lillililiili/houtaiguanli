import { describe, expect, it } from 'vitest'
import { actionSummary, conditionText, enabledCount, ruleDraft, scopeSummary, timeSummary } from '@/views/system/rules/ruleModel.js'

const catalog = [
  { code: 'confidence', label: '识别置信度', default_name: '识别置信度要求', kind: 'NUMBER', operator: '不低于', unit: '%', supports_hold: true },
  { code: 'identity', label: '目标身份关联', default_name: '目标身份可关联', kind: 'FIXED', fixed_value: '身份标识与当前目标明确关联', supports_hold: false }
]

describe('规则管理展示模型', () => {
  it('数字规则保留字符串值并拼出完整条件', () => {
    const draft = ruleDraft(null, catalog)
    expect(draft).toMatchObject({ item_code: 'confidence', value: '', name: '识别置信度要求' })
    expect(conditionText({ item_code: 'confidence', value: '95' }, catalog)).toBe('识别置信度不低于 95 %')
  })

  it('零条启用规则保持暂停语义', () => {
    expect(enabledCount({ rules: [{ enabled: false }, { enabled: false }] })).toBe(0)
  })

  it('完整展示指定空域、跨午夜时间，通知处罚规则对应通知处罚部门', () => {
    const settings = { scope_mode: 'AIRSPACES', airspace_names: ['机场净空区', '港区'], schedule_mode: 'DAILY', start_time: '22:00', end_time: '06:00', actions: [] }
    expect(scopeSummary(settings)).toBe('机场净空区、港区')
    expect(timeSummary(settings)).toBe('22:00 至 06:00（次日） · 北京时间')
    expect(actionSummary('dispose', settings)).toBe('通知处罚部门')
  })

  it('反制规则文案不把配置开关表达为授权', () => {
    expect(actionSummary('counter', {})).toBe('执行前仍需独立校验有效授权')
  })
})
