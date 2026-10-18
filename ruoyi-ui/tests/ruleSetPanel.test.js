import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import RuleSetPanel from '@/views/system/rules/RuleSetPanel.vue'
import { RECOMMENDED_RULE_SET_VERSION_IDS, isDemoVersion, isRecommendedVersion, versionSummary } from '@/views/system/rules/ruleSetPresentation.js'
import { ruleSetApi } from '@/api/ruleSets'

const permissions = vi.hoisted(() => ({ codes: new Set() }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: code => permissions.codes.has(code) }) }))
vi.mock('@/api/ruleSets', () => ({ ruleSetApi: { list: vi.fn(), versions: vi.fn(), activate: vi.fn() } }))
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn() }, ElMessageBox: { prompt: vi.fn() } }))

let app, host
const legality = { rule_set_id: 'rs1', rule_set_code: 'LEGALITY-DEMO', name: '合法性研判演示规则集', active_version_id: null, version: 0 }
const version = (no, extra = {}) => ({ rule_set_version_id: `v${no}`, rule_set_code: 'LEGALITY-DEMO', version_no: no, status_code: 'PUBLISHED', param_status: 'DEMO',
  description: `第${no}版说明`, published_at: 1_790_000_000_000, is_active: false, is_shadow: false, activation_allowed: true, ...extra })
async function settle() { for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() } }
async function mount() { host = document.createElement('div'); document.body.append(host); app = createApp(RuleSetPanel); app.use(ElementPlus); app.mount(host); await settle() }
const buttons = text => [...document.querySelectorAll('button')].filter(el => el.textContent.trim() === text)

describe('研判规则集启用', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    permissions.codes = new Set(['rule:read', 'rule:manage'])
    ruleSetApi.list.mockResolvedValue({ items: [legality], total: 1 })
    ruleSetApi.versions.mockResolvedValue({ items: [version(2), version(1)], total: 2 })
    ruleSetApi.activate.mockResolvedValue({ ...legality, active_version_id: 'v1', version: 1 })
    ElMessageBox.prompt.mockResolvedValue({ value: ' 新系统准备 ' })
  })
  afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

  it('没有生效版本时明确提示，系统不会按这套规则研判', async () => {
    await mount()
    expect(ruleSetApi.versions).toHaveBeenCalledWith('LEGALITY-DEMO')
    expect(host.textContent).toContain('合法性研判演示规则集')
    expect(host.textContent).toContain('尚未启用，系统不会按这套规则研判')
    expect(host.textContent).toContain('演示参数（不建议用于交付）')
    expect([...host.querySelectorAll('.version-details .version-id')].map(el => el.textContent)).toEqual(['版本编号：v2', '版本编号：v1'])
  })

  it('启用时填写原因并带上规则集版本号，完成后重新读取', async () => {
    await mount()
    buttons('启用此版本')[1].click(); await settle()
    expect(ElMessageBox.prompt).toHaveBeenCalledWith(expect.stringContaining('第 1 版'), '启用第 1 版', expect.any(Object))
    expect(ElMessageBox.prompt.mock.calls[0][0]).not.toContain('（v1）')
    expect(ruleSetApi.activate).toHaveBeenCalledWith('LEGALITY-DEMO', { rule_set_version_id: 'v1', note: '新系统准备', expected_version: 0 })
    expect(ElMessage.success).toHaveBeenCalled()
    expect(ruleSetApi.list).toHaveBeenCalledTimes(2)
  })

  it('取消填写原因不发请求', async () => {
    ElMessageBox.prompt.mockRejectedValue('cancel')
    await mount()
    buttons('启用此版本')[0].click(); await settle()
    expect(ruleSetApi.activate).not.toHaveBeenCalled()
  })

  it('正式环境的演示参数和生效版本不给启用按钮，只说明原因', async () => {
    ruleSetApi.versions.mockResolvedValue({ items: [
      version(2, { activation_allowed: false, activation_block_reason: '演示参数尚未经业务方确认，正式环境不能启用' }),
      version(1, { param_status: 'CONFIRMED', is_active: true, activation_allowed: false, activation_block_reason: '该版本已是生效版本' })
    ], total: 2 })
    await mount()
    expect(buttons('启用此版本')).toHaveLength(0)
    expect(host.textContent).toContain('演示参数尚未经业务方确认，正式环境不能启用')
    expect(host.textContent).toContain('当前生效：第 1 版')
    expect(host.textContent).toContain('已确认参数')
  })

  it('只有查看权限时不给启用按钮', async () => {
    permissions.codes = new Set(['rule:read'])
    await mount()
    expect(buttons('启用此版本')).toHaveLength(0)
    expect(host.textContent).toContain('启用需要规则管理权限')
  })

  it('启用冲突时提示刷新后核对', async () => {
    ruleSetApi.activate.mockRejectedValue(Object.assign(new Error('规则集已被其他操作更新'), { code: 'VERSION_CONFLICT', status: 409 }))
    await mount()
    buttons('启用此版本')[0].click(); await settle()
    expect(ElMessage.error).toHaveBeenCalledWith(expect.stringContaining('已刷新'))
    expect(ruleSetApi.list).toHaveBeenCalledTimes(2)
  })
})

describe('研判规则版本的说明摘要与推荐标记', () => {
  const sha = '4cec49368ba934683290bef91686d6227b6a4d94444f3e4aa194f653a34c5dd5'
  beforeEach(() => {
    vi.clearAllMocks()
    permissions.codes = new Set(['rule:read'])
  })
  afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

  it('推荐版本集中配置为已确认的最新合法性与空间风险版本', () => {
    expect(RECOMMENDED_RULE_SET_VERSION_IDS).toEqual(['legality-current-20261009', 'space-bird-spatial-20261011'])
    expect(isRecommendedVersion({ rule_set_version_id: 'space-bird-spatial-20261011' })).toBe(true)
    expect(isRecommendedVersion({ rule_set_version_id: 'space-current-20261009' })).toBe(false)
    expect(isRecommendedVersion({ rule_set_version_id: 'x' }, ['x'])).toBe(true)
  })

  it.each([
    [`第一句说明。第二句较长的补充说明。SHA-256:${sha}`, '第一句说明。'],
    [`没有句号的说明 SHA-256: ${sha}`, '没有句号的说明。'],
    ['', '暂无说明']
  ])('说明摘要只保留第一句话且去掉校验码：%s', (description, expected) => {
    expect(versionSummary(description)).toBe(expected)
  })

  it.each([
    [`2026-10-09用户确认按项目现用参数执行；依据《规则与做法确认书-新版-2026-10-08》及本轮确认。保持现用数值。SHA-256:${sha}`, '10-09 确认：按项目现用参数执行。'],
    ['v2：C02-3 航线偏离容差放宽到 50 m（未激活，供影子/激活演示）', '航线偏离容差放宽到 50 米。'],
    ['C04/C05 演示阈值，未经业务方确认', '演示阈值，未经业务方确认。'],
    [`按《规则与做法确认书（含验收方法）-2026-10-08 .docx》第四部分4-1至4-6发布；C04/C05规则及参数已确认，4-1按2026-10-08本会话补充确认沿用AGL分层。SHA-256:${sha}`, '规则及参数已确认，按10-08补充确认沿用离地高度分层。'],
    ['2026-11-02本会话最新确认：C03.no_plan_status 改为提示；其余沿用。', '11-02 确认：改为提示；其余沿用。']
  ])('摘要只写业务含义，不带条款编号、文件名、版本号：%s', (description, expected) => {
    const summary = versionSummary(description)
    expect(summary).toBe(expected)
    expect(summary).not.toMatch(/[A-Z]\d{2}|docx|《|\bv\d|SHA/i)
  })

  it('演示参数版本标“不建议用于交付”，仍留在列表里，能否启用不变', async () => {
    expect(isDemoVersion({ param_status: 'DEMO' })).toBe(true)
    expect(isDemoVersion({ param_status: 'CONFIRMED' })).toBe(false)
    ruleSetApi.list.mockResolvedValue({ items: [{ rule_set_id: 'rs-s', rule_set_code: 'SPACE', name: '空间风险', version: 1 }], total: 1 })
    ruleSetApi.versions.mockResolvedValue({ items: [
      version(2, { rule_set_version_id: 'space-x', param_status: 'CONFIRMED', description: '已确认。' }),
      version(1, { rule_set_version_id: 'space-demo-y', param_status: 'DEMO', description: 'C04/C05 演示阈值，未经业务方确认', activation_allowed: true })
    ], total: 2 })
    await mount()
    const rows = [...host.querySelectorAll('.el-table__body tr')]
    expect(rows).toHaveLength(2)
    expect(rows[1].textContent).toContain('演示参数（不建议用于交付）')
    expect(rows[1].querySelector('.demo-tag')?.textContent.trim()).toBe('演示参数')
    expect(rows[0].querySelector('.demo-tag')).toBeNull()
    expect(rows[1].querySelector('.version-summary').textContent).toBe('演示阈值，未经业务方确认。')
    expect(rows[1].querySelector('.version-details').textContent).toContain('C04/C05')
    expect(ruleSetApi.activate).not.toHaveBeenCalled()
  })

  it('列表显示摘要和推荐标记，完整说明、校验码和版本编号收进详细信息，不改变启用状态', async () => {
    ruleSetApi.list.mockResolvedValue({ items: [{ rule_set_id: 'rs-l', rule_set_code: 'LEGALITY', name: '合法性研判', version: 3 }], total: 1 })
    ruleSetApi.versions.mockResolvedValue({ items: [
      version(6, { rule_set_version_id: 'legality-current-20261009', param_status: 'CONFIRMED', description: `按项目现用参数执行。保持现用数值。SHA-256:${sha}` }),
      version(1, { rule_set_version_id: 'legality-demo', is_active: true, description: '演示参数。' })
    ], total: 2 })
    await mount()
    const rows = [...host.querySelectorAll('.el-table__body tr')]
    expect(rows[0].querySelector('.recommended-tag')?.textContent.trim()).toBe('推荐')
    expect(rows[1].querySelector('.recommended-tag')).toBeNull()
    expect(rows[0].querySelector('.version-summary').textContent).toBe('按项目现用参数执行。')
    expect(rows[0].querySelector('.version-summary').textContent).not.toContain(sha)
    expect(rows[0].querySelector('.version-details').textContent).toContain(sha)
    expect(rows[0].querySelector('.version-details').open).toBe(false)
    expect(host.querySelector('.rule-set header').textContent).not.toContain('LEGALITY')
    expect(host.textContent).toContain('当前生效：第 1 版')
    expect(ruleSetApi.activate).not.toHaveBeenCalled()
  })
})

