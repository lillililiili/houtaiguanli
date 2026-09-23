import { describe, expect, it } from 'vitest'
import { requiresReload, configurationError } from '@/utils/directoryConfiguration'

describe('目录资料保存冲突', () => {
  it('版本冲突与结果未知必须重读，业务校验失败保留可修正状态', () => {
    expect(requiresReload({ status: 409 })).toBe(true)
    expect(requiresReload({ code: 'NETWORK_ERROR', status: 0 })).toBe(true)
    expect(requiresReload({ status: 400 })).toBe(false)
    expect(configurationError({ status: 409, message: '版本冲突' })).toContain('未覆盖')
    expect(configurationError({ status: 0 })).toContain('结果尚未确认')
  })
})
