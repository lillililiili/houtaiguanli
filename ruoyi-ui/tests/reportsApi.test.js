import { describe, expect, it } from 'vitest'
import { createLatestRequestGuard, normalizeReportPreview, reportFilename } from '@/api/reports'

describe('报表接口适配', () => {
  it('将 snake_case 报表字段映射为页面模型', () => {
    const result = normalizeReportPreview({
      report_type: 'WEEKLY', anchor_date: '2026-03-04', period_label: '2026 年第 10 周周报', generated_at: 1,
      report: {
        from: '2026-03-02', to: '2026-03-04', source_mode: 'mock', simulated: true,
        summary: { total: 9, high_risk: 2 }, days: [{ date: '2026-03-02', md: '03-02', total: 3, high_risk: 1 }],
        by_risk: [{ name: '高风险', value: 2 }], partners: [{ name: '某单位', case_count: 1, fine: 500 }]
      }
    })
    expect(result.reportType).toBe('WEEKLY')
    expect(result.report.summary.highRisk).toBe(2)
    expect(result.report.days[0].highRisk).toBe(1)
    expect(result.report.partners[0]).toEqual({ name: '某单位', caseCount: 1, fine: 500 })
    expect(reportFilename(result)).toBe('低空安全运行周报-20260302-20260304.xlsx')
  })

  it('只允许最新一次请求更新预览', () => {
    const guard = createLatestRequestGuard()
    const stale = guard.begin()
    const current = guard.begin()
    expect(guard.isCurrent(stale)).toBe(false)
    expect(guard.isCurrent(current)).toBe(true)
  })
})
