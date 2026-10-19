import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';
import { formatTime } from '@/utils/format.js';

describe('后台北京时间展示', () => {
  it.each(['UTC', 'America/New_York', 'Asia/Shanghai'])('电脑时区为 %s 时，列表和趋势仍按北京时间跨日', timezone => {
    const formatUrl = pathToFileURL(resolve('src/utils/format.js')).href;
    const trendUrl = pathToFileURL(resolve('src/utils/deviceTrends.js')).href;
    const result = JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', `
      import { formatTime } from ${JSON.stringify(formatUrl)};
      import { chartOption } from ${JSON.stringify(trendUrl)};
      const dates = ['2027-01-01T16:15:00Z', '2027-06-30T16:15:00Z'];
      console.log(JSON.stringify(dates.map(value => {
        const at = Date.parse(value);
        return { text: formatTime(at), labels: chartOption({ from: at, to: at + 60000, bucket_ms: 60000 }, { series: [] }).xAxis.data };
      })));
    `], { encoding: 'utf8', env: { ...process.env, TZ: timezone } }));
    expect(result).toEqual([
      { text: '2027/1/2 00:15:00', labels: ['01/02\n00:15', '01/02\n00:16'] },
      { text: '2027/7/1 00:15:00', labels: ['07/01\n00:15', '07/01\n00:16'] }
    ]);
  });

  it('保留空值、无效值和字符串毫秒的既有语义', () => {
    for (const value of [null, undefined, '']) expect(formatTime(value)).toBe('—');
    expect(formatTime('invalid')).toBe('invalid');
    expect(formatTime(String(Date.parse('2027-01-01T16:15:00Z')))).toBe('2027/1/2 00:15:00');
  });
});
