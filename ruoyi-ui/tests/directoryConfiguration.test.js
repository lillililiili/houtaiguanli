import { describe, expect, it } from 'vitest'
import { fixedRecipient, notificationBody, requiresReload, configurationError } from '@/utils/directoryConfiguration'

describe('通知对象配置业务边界', () => {
  const input = { channel_type: 'MOCK', endpoint_ref: ' local-risk ', enabled: true, valid_until: '1234567890', recipient_org_id: 'ORG-OTHER', contact_id: 'CONTACT-OTHER', source_binding_id: 'BINDING-OTHER' }
  it('风险固定通知上级，不向契约夹带单位、联系人或来源路由', () => {
    const body = notificationBody({ ...input, purpose: 'RISK_NOTICE' }, 4)
    expect(fixedRecipient(body.purpose)).toBe('上级')
    expect(body).toEqual({ purpose: 'RISK_NOTICE', channel_type: 'MOCK', endpoint_ref: 'local-risk', enabled: true, valid_until: 1234567890, expected_version: 4 })
  })
  it.each(['ADVISORY_SMS', 'ADVISORY_VOICE'])('飞手通知 %s 不使用配置中遗留的固定单位联系人', purpose => {
    const body = notificationBody({ ...input, purpose }, 2)
    expect(body.contact_id).toBeUndefined()
    expect(body.recipient_org_id).toBeUndefined()
    expect(body.source_binding_id).toBeUndefined()
    expect(fixedRecipient(purpose)).toContain('已核实飞手')
  })
  it('计划反馈采用来源映射，不能把单位 ID 放进技术来源接收方字段', () => {
    const body = notificationBody({ ...input, purpose: 'PLAN_FEEDBACK' }, 0)
    expect(body.source_binding_id).toBe('BINDING-OTHER')
    expect(body.contact_id).toBe('CONTACT-OTHER')
    expect(body.recipient_org_id).toBeUndefined()
    expect(body.recipient_id).toBeUndefined()
    expect(body.expected_version).toBe(0)
  })
  it('处罚接收单位独立配置，不复用计划来源映射', () => {
    const body = notificationBody({ ...input, purpose: 'UAV_PUNISHMENT', contact_id: '', valid_until: '', enabled: false })
    expect(body.recipient_org_id).toBe('ORG-OTHER')
    expect(body.contact_id).toBeNull()
    expect(body.source_binding_id).toBeUndefined()
    expect(body.expected_version).toBeUndefined()
    expect(body.enabled).toBe(false)
    expect(body.valid_until).toBeNull()
  })
  it('版本冲突与结果未知必须重读，业务校验失败保留可修正状态', () => {
    expect(requiresReload({ status: 409 })).toBe(true)
    expect(requiresReload({ code: 'NETWORK_ERROR', status: 0 })).toBe(true)
    expect(requiresReload({ status: 400 })).toBe(false)
    expect(configurationError({ status: 409, message: '版本冲突' })).toContain('未覆盖')
    expect(configurationError({ status: 0 })).toContain('结果尚未确认')
  })
})
