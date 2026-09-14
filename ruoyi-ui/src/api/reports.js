import { download, queryString, request } from '@/services/apiClient.js';

const named = rows => (rows || []).map(item => ({ name: item.name, value: Number(item.value || 0) }));

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
      summary: {
        total: Number(summary.total || 0), illegal: Number(summary.illegal || 0),
        punish: Number(summary.punish || 0), highRisk: Number(summary.high_risk || 0),
        uav: Number(summary.uav || 0), abnormal: Number(summary.abnormal || 0)
      },
      devices: source.devices ? {
        total: Number(source.devices.total || 0), online: Number(source.devices.online || 0),
        onlineRate: source.devices.online_rate == null ? null : Number(source.devices.online_rate)
      } : null,
      days: (source.days || []).map(item => ({
        date: item.date, md: item.md, total: Number(item.total || 0), illegal: Number(item.illegal || 0),
        punish: Number(item.punish || 0), highRisk: Number(item.high_risk || 0)
      })),
      byRisk: named(source.by_risk), byType: named(source.by_type),
      byDuration: named(source.by_duration), byTrack: named(source.by_track),
      altBands: named(source.alt_bands), altTotal: Number(source.alt_total || 0),
      regions: (source.regions || []).map(item => ({
        name: item.name, total: Number(item.total || 0), illegal: Number(item.illegal || 0),
        punish: Number(item.punish || 0), highRisk: Number(item.high_risk || 0)
      })),
      byPenalty: named(source.by_penalty),
      partners: (source.partners || []).map(item => ({
        name: item.name, caseCount: Number(item.case_count || 0), fine: Number(item.fine || 0)
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
