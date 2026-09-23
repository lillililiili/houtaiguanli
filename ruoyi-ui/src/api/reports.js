import { download, queryString, request } from '@/services/apiClient.js';

const number = value => value == null ? null : Number(value);

const named = rows => (rows || []).map(item => ({ name: item.name, value: number(item.value) }));

export function normalizeReportPreview(payload = {}) {
  const source = payload.report || {};
  const summary = source.summary || {};
  return {
    reportType: payload.report_type || '',
    anchorDate: payload.anchor_date || '',
    periodLabel: payload.period_label || '',
    generatedAt: payload.generated_at || null,
    report: {
      from: source.from || '',
      to: source.to || '',
      sourceMode: source.source_mode || 'unknown',
      simulated: Boolean(source.simulated),
      generatedAt: source.generated_at ?? payload.generated_at ?? null,
      availability: source.availability || {},
      summary: {
        total: number(summary.total), illegal: number(summary.illegal),
        punish: number(summary.punish), highRisk: number(summary.high_risk),
        uav: number(summary.uav), abnormal: number(summary.abnormal)
      },
      devices: source.devices ? {
        total: number(source.devices.total), online: number(source.devices.online),
        onlineRate: source.devices.online_rate == null ? null : Number(source.devices.online_rate)
      } : null,
      days: (source.days || []).map(item => ({
        date: item.date, md: item.md, total: number(item.total), illegal: number(item.illegal),
        punish: number(item.punish), highRisk: number(item.high_risk)
      })),
      byRisk: named(source.by_risk), byType: named(source.by_type),
      byDuration: named(source.by_duration), byTrack: named(source.by_track),
      altBands: named(source.alt_bands), altTotal: number(source.alt_total),
      regions: (source.regions || []).map(item => ({
        name: item.name, total: number(item.total), illegal: number(item.illegal),
        punish: number(item.punish), highRisk: number(item.high_risk)
      })),
      byPenalty: named(source.by_penalty),
      partners: (source.partners || []).map(item => ({
        name: item.name, caseCount: number(item.case_count), fine: number(item.fine)
      }))
    }
  };
}

export function reportFilename(preview) {
  const type = preview?.reportType;
  const report = preview?.report || {};
  const compact = value => String(value || '').replaceAll('-', '');
  if (type === 'DAILY') return `低空安全运行日报-${compact(report.from)}.xlsx`;
  if (type === 'WEEKLY') return `低空安全运行周报-${compact(report.from)}-${compact(report.to)}.xlsx`;
  return `低空安全运行月报-${compact(report.from).slice(0, 6)}.xlsx`;
}

export function createLatestRequestGuard() {
  let sequence = 0;
  return { begin: () => ++sequence, isCurrent: value => value === sequence };
}

export const reportApi = {
  preview: params => request({ url: `/v1/stats/reports/preview${queryString(params)}` }).then(normalizeReportPreview),
  exportExcel: (params, filename) => download(`/v1/stats/reports/export.xlsx${queryString(params)}`, filename)
};

export const businessReportApi = {
  preview: params => request({ url: `/v1/stats/reports/preview${queryString(params)}` }),
  details: params => request({ url: `/v1/stats/reports/details${queryString(params)}` }),
  exportFile: (params, format, filename) => download(`/v1/stats/reports/export.${format}${queryString(params)}`, filename, { timeout: 120000 })
};

export function businessReportFilename(preview, format) {
  const period = { DAILY: '日报', WEEKLY: '周报', MONTHLY: '月报' }[preview.period_type] || '';
  return `${preview.title}${period}-${preview.from}-${preview.to}.${format}`;
}
