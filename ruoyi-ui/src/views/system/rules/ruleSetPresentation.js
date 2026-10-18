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

const CHECKSUM = /\s*(?:SHA-?256|校验码)\s*[:：]?\s*[0-9a-f]{64}\s*/gi

// 去掉校验码后只取第一句话作为摘要；完整说明放进“详细信息”。
export function versionSummary(description, maxLength = 80) {
  const text = String(description || '').replace(CHECKSUM, ' ').replace(/\s+/g, ' ').trim()
  if (!text) return '暂无说明'
  const first = text.match(/^[\s\S]*?[。！？!?]/)?.[0]?.trim() || text
  return first.length > maxLength ? `${first.slice(0, maxLength - 1)}…` : first
}
