import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError, newIdempotencyKey, queryString, readToken, SESSION_KEY, writeToken } from '@/services/apiClient'
import { userFacingMessage } from '@/utils/userMessages'

describe('API 客户端约定', () => {
  beforeEach(() => sessionStorage.clear())

  it('优化提示时保留错误码、状态及未知的具体原因', () => {
    const error = new ApiError('通知配置已超过有效期', 'CHANNEL_UNAVAILABLE', 409)
    expect(error.message).toBe('通知设置已到期，暂时发不了通知。请联系管理员更新设置。')
    expect(error.code).toBe('CHANNEL_UNAVAILABLE')
    expect(error.status).toBe(409)
    expect(userFacingMessage('未收录的具体原因')).toBe('未收录的具体原因')
    expect(userFacingMessage('toString')).toBe('toString')
    expect(userFacingMessage(null)).toBe('')
    expect(userFacingMessage('DELIVERY_OUTCOME_UNKNOWN')).toContain('不要重复发送')
  })

  it('后台会话只保存在 sessionStorage', () => {
    writeToken('admin-session')
    expect(sessionStorage.getItem(SESSION_KEY)).toBe('admin-session')
    expect(readToken()).toBe('admin-session')
    writeToken('')
    expect(readToken()).toBe('')
  })

  it('查询参数保留 0 和 false，忽略空值', () => {
    expect(queryString({ page: 1, enabled: false, count: 0, empty: '', nil: null })).toBe('?page=1&enabled=false&count=0')
  })

  it('为写请求生成不同幂等键', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey())
  })
})
