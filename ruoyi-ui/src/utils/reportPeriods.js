const REPORT_ZONE = 'Asia/Shanghai';

function two(value) {
  return String(value).padStart(2, '0');
}

function parseIso(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value || '');
  if (!match) throw new RangeError('日期必须是 YYYY-MM-DD');
  return new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
}

function iso(date) {
  return `${date.getUTCFullYear()}-${two(date.getUTCMonth() + 1)}-${two(date.getUTCDate())}`;
}

export function shanghaiToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: REPORT_ZONE,
    year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(now);
  const values = Object.fromEntries(parts.map(item => [item.type, item.value]));
  return `${values.year}-${values.month}-${values.day}`;
}

export function pickerValue(reportType, today = shanghaiToday()) {
  return reportType === 'MONTHLY' ? today.slice(0, 7) : today;
}

export function normalizeAnchor(reportType, value) {
  if (reportType === 'MONTHLY') {
    if (!/^\d{4}-\d{2}$/.test(value || '')) throw new RangeError('月份必须是 YYYY-MM');
    return `${value}-01`;
  }
  parseIso(value);
  return value;
}

export function resolveReportPeriod(reportType, anchorDate, today = shanghaiToday()) {
  const anchor = parseIso(anchorDate);
  const current = parseIso(today);
  if (anchor > current) throw new RangeError('不能选择未来日期');

  let from = new Date(anchor);
  let to = new Date(anchor);
  if (reportType === 'WEEKLY') {
    const mondayOffset = (anchor.getUTCDay() + 6) % 7;
    from.setUTCDate(from.getUTCDate() - mondayOffset);
    to = new Date(from);
    to.setUTCDate(to.getUTCDate() + 6);
  } else if (reportType === 'MONTHLY') {
    from = new Date(Date.UTC(anchor.getUTCFullYear(), anchor.getUTCMonth(), 1));
    to = new Date(Date.UTC(anchor.getUTCFullYear(), anchor.getUTCMonth() + 1, 0));
  } else if (reportType !== 'DAILY') {
    throw new RangeError('不支持的报表类型');
  }

  if (to > current) to = current;
  return { from: iso(from), to: iso(to) };
}

export function isFuturePickerDate(time, reportType, today = shanghaiToday()) {
  const value = `${time.getFullYear()}-${two(time.getMonth() + 1)}-${two(time.getDate())}`;
  return reportType === 'MONTHLY' ? value.slice(0, 7) > today.slice(0, 7) : value > today;
}
