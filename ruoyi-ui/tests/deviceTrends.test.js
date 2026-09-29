import { describe, expect, it } from 'vitest';
import { chartOption, trendModel } from '@/utils/deviceTrends.js';
const point = (code, at, latest, samples = 1) => ({ code, at, latest, average: latest, minimum: latest, maximum: latest, samples, last_at: at });
const fixture = () => ({ protocol_code: 'RADAR_TCP_V3_0_0', from: 0, to: 180000, bucket_ms: 60000, metrics: [point('active_track_count', 0, 0), point('active_track_count', 120000, 3), point('rtk_satellite_count', 120000, 18)], reports: [point('report_track', 0, 1, 30), point('report_point', 0, 1, 20)] });
describe('设备运行趋势统计口径', () => {
  it('保留空目标帧的 0，缺失时间桶为 null，不连接缺口', () => {
    const data = fixture(), model = trendModel(data, 'sensing');
    expect(model.cards[0].value).toBe(3);
    expect(model.cards[1].value).toBe(1.5);
    const option = chartOption(data, model.charts[0]);
    expect(option.series[0].data).toEqual([0, null, 3, null]);
    expect(option.series[0].connectNulls).toBe(false);
  });
  it('各报文独立求和，间隔使用同类接收间隔而不是报文计数', () => {
    const data = fixture(); data.reports[0].interval_seconds = 2.5;
    const model = trendModel(data, 'reports', 'report_track');
    expect(model.cards[0].value).toBe(50);
    expect(chartOption(data, model.charts[1]).series[0].data[0]).toBe(2.5);
    expect(model.charts[0].series).toHaveLength(2);
  });
  it('混合状态桶保留未知，不将平均值解释成状态或在线时长', () => {
    const data = fixture(); data.metrics.push({ ...point('connection_state', 0, 1), minimum: 0, maximum: 1 });
    const model = trendModel(data, 'state');
    expect(model.cards).toEqual([]);
    expect(chartOption(data, model.charts[0]).series[0].data).toEqual([null, null, null, null]);
  });
  it('感知摘要统计整个时段，均值按帧数加权，不重复当前值', () => {
    const data = fixture();
    data.metrics = [point('active_track_count', 0, 10, 3), point('active_track_count', 60000, 2, 1)];
    const model = trendModel(data, 'sensing');
    expect(model.cards.map(card => card.value)).toEqual([10, 8]);
    expect(model.cards.some(card => card.label.includes('最近'))).toBe(false);
  });
  it('无数据时不伪造零值统计，MQTT 不显示雷达点迹与卫星数', () => {
    const data = { ...fixture(), protocol_code: 'LINGYUN_MQTT_V8_6', metrics: [], reports: [] };
    const model = trendModel(data, 'sensing');
    expect(model.cards.every(c => c.value === '—')).toBe(true);
    expect(JSON.stringify(model)).not.toContain('卫星');
    expect(model.charts[0].title).toContain('感知目标');
  });
});
