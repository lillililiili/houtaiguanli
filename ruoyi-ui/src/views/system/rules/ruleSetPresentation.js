// 研判规则版本的展示口径（#30）。
//
// “推荐”只是页面标记，不启用、不切换任何版本；启用仍走有权限、有版本校验、有审计的入口。
// 推荐哪一版待用户最终决定，当前按“已确认的最新版”：
//   合法性研判  legality-current-20261009（2026-10-09 现用参数确认）
//   空间风险    space-bird-spatial-20261011（2026-10-11 鸟类空间风险确认）
// 要切换推荐版本只改这一个列表。
export const RECOMMENDED_RULE_SET_VERSION_IDS = Object.freeze([
  'legality-current-20261009',
  'space-bird-spatial-20261011'
])

export function isRecommendedVersion(version, recommended = RECOMMENDED_RULE_SET_VERSION_IDS) {
  return Boolean(version?.rule_set_version_id) && recommended.includes(version.rule_set_version_id)
}

const CHECKSUM = /\s*(?:原文)?\s*(?:SHA-?256|校验码)\s*[:：]?\s*[0-9a-f]{64}\s*/gi
// 条款编号（C02-3、C04/C05、C03.no_plan_status）、确认书章节号（2-1至2-13）、开头的版本号（v2：）属于内部写法。
const CLAUSE = /[A-Z]\d{2}(?:-\d+)?(?:\.[a-z_]+)?(?:\s*[/、]\s*[A-Z]\d{2}(?:-\d+)?)*/g
const SECTION = /(?:第[一二三四五六七八九十]+部分)?(?<![\d-])\d-\d{1,2}(?:\s*[至~]\s*\d-\d{1,2})?(?![\d-])/g
const VERSION_PREFIX = /^v\d+\s*[：:]\s*/i
// 括号里的内部运维说明（影子验证、激活演示等）不进摘要。
const INTERNAL_NOTE = /[（(][^）)]*(?:影子|激活|演示)[^）)]*[）)]/g
const DATE_LEAD = /^(?:20\d{2}-)?(\d{2})-(\d{2})\s*(?:本会话|本轮)?(?:最新)?(?:用户)?确认\s*[：:]?\s*/
const DATE_INLINE = /20\d{2}-(\d{2})-(\d{2})\s*(?:本会话|本轮)?/g

function sentences(text) {
  return (text.match(/[^。！？!?]+[。！？!?]?/g) || []).map(item => item.trim()).filter(Boolean)
}

function plainSentence(sentence) {
  const end = /[。！？!?]$/.test(sentence) ? sentence.slice(-1) : ''
  const body = end ? sentence.slice(0, -1) : sentence
  // 引用文件名（《……docx》）的分句整句不要：说明的是依据出处，不是业务含义。
  const clauses = body.split('；').filter(clause => !/《|docx/i.test(clause))
  let text = clauses.join('；')
    .replace(VERSION_PREFIX, '')
    .replace(DATE_LEAD, (_, month, day) => `${month}/${day} 确认：`)
    .replace(DATE_INLINE, (_, month, day) => `${month}/${day}`)
    .replace(INTERNAL_NOTE, '')
    .replace(CLAUSE, '')
    .replace(SECTION, '')
    .replace(/(\d{2})\/(\d{2})/g, '$1-$2')
    .replace(/AGL/g, '离地高度')
    .replace(/(\d)\s*m(?![a-z])/gi, '$1 米')
    .replace(/\s+/g, ' ')
    .replace(/^[\s，、；：]+|[\s，、；：]+$/g, '')
    .replace(/\s*([，、；：])\s*/g, '$1')
    .replace(/(^|[，；：])\s*按\s*(?=[，；：]|$)/g, '$1')
    .trim()
  return text ? `${text}${end || '。'}` : ''
}

/**
 * 版本说明的摘要（#30）：只写业务含义的一两句话。去掉校验码、文件名、条款编号、章节号、版本号和内部运维说明，
 * 日期写成“月-日 确认：”；完整说明（含这些编号）放进“详细信息”。不改动版本本身。
 */
export function versionSummary(description, maxLength = 90) {
  const text = String(description || '').replace(CHECKSUM, ' ').replace(/\s+/g, ' ').trim()
  if (!text) return '暂无说明'
  const parts = sentences(text).map(plainSentence).filter(Boolean)
  if (!parts.length) return '暂无说明'
  const summary = parts[0]
  return summary.length > maxLength ? `${summary.slice(0, maxLength - 1)}…` : summary
}

/** 演示参数版本：列表里标“演示参数（不建议用于交付）”，不删除、不改变能否启用。 */
export const DEMO_PARAM_LABEL = '演示参数（不建议用于交付）'
export function isDemoVersion(version) {
  return version?.param_status === 'DEMO'
}
