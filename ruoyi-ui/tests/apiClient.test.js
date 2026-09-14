import { beforeEach, describe, expect, it } from 'vitest'
import { newIdempotencyKey, queryString, readToken, SESSION_KEY, writeToken } from '@/services/apiClient'

describe('API 客户端约定', () => {
  beforeEach(() => sessionStorage.clear())

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
