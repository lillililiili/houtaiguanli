import { describe, expect, it } from 'vitest'
import { isFuturePickerDate, normalizeAnchor, resolveReportPeriod, shanghaiToday } from '@/utils/reportPeriods'

describe('报表自然周期', () => {
  it('日报保持单日，当前周和当前月截止上海当天', () => {
    expect(resolveReportPeriod('DAILY', '2026-03-04', '2026-03-04')).toEqual({ from: '2026-03-04', to: '2026-03-04' })
    expect(resolveReportPeriod('WEEKLY', '2026-03-04', '2026-03-04')).toEqual({ from: '2026-03-02', to: '2026-03-04' })
    expect(resolveReportPeriod('MONTHLY', '2026-03-01', '2026-03-04')).toEqual({ from: '2026-03-01', to: '2026-03-04' })
  })

  it('计算跨年自然周和闰年月末', () => {
    expect(resolveReportPeriod('WEEKLY', '2026-01-01', '2026-03-04')).toEqual({ from: '2025-12-29', to: '2026-01-04' })
    expect(resolveReportPeriod('MONTHLY', '2024-02-10', '2026-03-04')).toEqual({ from: '2024-02-01', to: '2024-02-29' })
  })

  it('按上海时区取业务日期并禁用未来日期', () => {
    expect(shanghaiToday(new Date('2026-03-03T16:30:00Z'))).toBe('2026-03-04')
    expect(normalizeAnchor('MONTHLY', '2026-03')).toBe('2026-03-01')
    expect(isFuturePickerDate(new Date(2026, 2, 5), 'DAILY', '2026-03-04')).toBe(true)
    expect(() => resolveReportPeriod('DAILY', '2026-03-05', '2026-03-04')).toThrow('未来')
  })
})
