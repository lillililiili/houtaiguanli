const LABELS = { active_track_count: '每帧航迹数', sensing_target_count: '每帧感知目标数', latest_point_count: '每帧点迹数', rtk_satellite_count: 'RTK 卫星数', report_track: '航迹报文', report_point: '点迹报文', report_rtk: 'RTK 报文', report_static: '工参报文', report_heartbeat: '心跳报文', report_sensing: '感知报文', report_status: '状态报文', report_other: '其他有效报文' };
export const trendLabel = code => LABELS[code] || (code.startsWith('channel_') ? `通道 ${code.slice(8).replaceAll('_', '.')}` : code);
export function trendModel(data, tab, reportCode) {
  const metrics = data?.metrics || [], reports = data?.reports || [];
  const radar = data?.protocol_code === 'RADAR_TCP_V3_0_0';
  const target = radar ? 'active_track_count' : 'sensing_target_count';
  const rows = (code, source = metrics) => source.filter(r => r.code === code);
  const peak = code => rows(code).length ? Math.max(...rows(code).map(r => r.maximum)) : null;
  const mean = code => {
    const values = rows(code).filter(r => Number.isFinite(r.average) && r.samples > 0);
    return values.length ? Number((values.reduce((sum, r) => sum + r.average * r.samples, 0) / values.reduce((sum, r) => sum + r.samples, 0)).toFixed(2)) : null;
  };
  const codes = [...new Set(reports.map(r => r.code))];
  const reportSum = code => { const values = code ? rows(code, reports) : reports; return values.length ? values.reduce((sum, r) => sum + r.samples, 0) : null; };
  const card = (label, value, unit = '') => ({ label, value: value ?? '—', unit });
  const line = (code, unit = '') => ({ title: `${trendLabel(code)}趋势`, unit, series: [{ name: trendLabel(code), rows: rows(code), field: 'average' }] });
  const reportChart = { title: '有效报文量', unit: `${Math.round((data?.bucket_ms || 60000) / 60000)} 分钟 / 桶`, bar: true, series: codes.map(code => ({ name: trendLabel(code), rows: rows(code, reports), field: 'samples' })) };
  const targetChart = line(target, '个 / 条');
  if (tab === 'reports' || (tab === 'sensing' && data?.sensing_supported === false)) return { cards: [card('时段有效报文总量', reportSum(), '条')], charts: [reportChart, { title: '同类报文接收间隔', unit: '秒 · 桶内均值', series: [{ name: trendLabel(reportCode || codes[0] || '报文'), rows: rows(reportCode || codes[0], reports), field: 'interval_seconds' }] }], note: '仅统计已接收且有效的报文；同类接收间隔不等同网络延迟。雷达统计已保存的航迹、点迹和 RTK 报文。' };
  if (tab === 'state') {
    const channel = [...new Set(metrics.map(r => r.code))].find(code => code.startsWith('channel_'));
    return { cards: [], charts: [{ title: '连接状态采样时间轴', unit: '0 离线 / 1 在线', state: true, series: [{ name: '连接状态', rows: rows('connection_state'), field: 'latest' }] }, radar ? line('rtk_satellite_count', '颗') : { title: channel ? '通道状态采样' : '工作状态码采样', unit: '协议原始状态码', state: true, series: [{ name: '状态码', rows: rows(channel || 'reported_work_state'), field: 'latest' }] }], note: '仅展示有记录的状态采样；同一时间桶内发生状态变化或缺少记录时保留空缺，不推算在线时长。' };
  }
  return { cards: [card('时段每帧目标峰值', peak(target), '个 / 条'), card('时段每帧目标均值', mean(target), '个 / 条')], charts: [targetChart, radar ? line('latest_point_count', '个') : { ...reportChart, title: '感知报文量', series: reportChart.series.filter(series => series.name === trendLabel('report_sensing')) }], note: '每帧目标数不等同去重目标总数；卡片统计所选时段的峰值和按帧数加权的均值。未收到数据保留空缺，不补 0。' };
}
export function chartOption(data, chart) {
  const times = [];
  for (let at = data.from; at <= data.to; at += data.bucket_ms) times.push(at);
  return { animation: false, color: ['#2685ff', '#26bec6', '#9b83f4', '#e5a632'],
    tooltip: { trigger: 'axis' }, legend: { top: 0, type: 'scroll' }, grid: { left: 48, right: 16, top: 55, bottom: 35 },
    xAxis: { type: 'category', boundaryGap: Boolean(chart.bar), axisLabel: { hideOverlap: true, showMaxLabel: false }, data: times.map(at => { const date = new Date(at); return `${date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })}\n${date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })}`; }) },
    yAxis: { type: 'value', minInterval: chart.state ? 1 : undefined, splitLine: { lineStyle: { color: '#edf1f7' } } },
    series: chart.series.map(series => { const byTime = new Map(series.rows.map(r => [r.at, r])); return { name: series.name, type: chart.bar ? 'bar' : 'line', stack: chart.bar ? 'reports' : undefined, step: chart.state ? 'end' : false, connectNulls: false, showSymbol: Boolean(chart.state) || series.rows.length <= 2, symbolSize: 5, areaStyle: chart.state || chart.bar ? undefined : { opacity: .07 }, data: times.map(at => { const row = byTime.get(at); return !row || (chart.state && row.minimum !== row.maximum) ? null : row[series.field] ?? null; }) }; }) };
}
