import { afterEach, describe, expect, it } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import RuleEditorDialog from '@/views/system/rules/RuleEditorDialog.vue'

let app, host
const catalog = [{ code: 'confidence', label: '识别置信度', default_name: '识别置信度要求', kind: 'NUMBER', operator: '不低于', unit: '%', min_value: 0, max_value: 100, supports_hold: true, source: '目标识别结果' }]
async function settle() { for (let index = 0; index < 8; index += 1) { await Promise.resolve(); await nextTick() } }
async function mount(props = {}) { host = document.createElement('div'); document.body.append(host); app = createApp(RuleEditorDialog, { modelValue: true, catalog, categoryLabel: '核实规则', ...props }); app.use(ElementPlus); app.mount(host); await settle() }
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

describe('规则编辑弹窗', () => {
  it('打开已有数字规则时保留服务端名称、值和持续时间', async () => { await mount({ rule: { rule_id: 'rule-1', name: '机场净空置信度', item_code: 'confidence', value: '87', hold_seconds: 6, enabled: true } }); const inputs = [...document.querySelectorAll('input')]; expect(inputs.some(input => input.value === '机场净空置信度')).toBe(true); expect(inputs.some(input => input.value === '87')).toBe(true); expect(inputs.some(input => input.value === '6')).toBe(true) })
  it('数字条件默认留空，填写前不能保存', async () => { let saved; await mount({ onSave: value => { saved = value } }); [...document.querySelectorAll('button')].find(button => button.textContent.trim() === '保存').click(); await settle(); expect(document.body.textContent).toContain('请填写条件数值'); expect(saved).toBeUndefined() })
  it('只向父组件提交字符串 value 和可选持续时间', async () => { let saved; await mount({ onSave: value => { saved = value } }); const number = [...document.querySelectorAll('input')].find(input => input.type === 'number'); number.value = '95'; number.dispatchEvent(new Event('input')); await settle(); [...document.querySelectorAll('button')].find(button => button.textContent.trim() === '保存').click(); await settle(); expect(saved).toMatchObject({ name: '识别置信度要求', item_code: 'confidence', value: '95', hold_seconds: 0, enabled: true }) })
  it('执行服务已连接时说明保存用于下一轮判定', async () => { await mount({ executionStatus: 'CONNECTED' }); expect(document.body.textContent).toContain('保存后用于下一轮自动判定'); expect(document.body.textContent).toContain('不会立即触发动作') })
  it('执行服务不可用时展示服务端解释且不宣称会执行', async () => { await mount({ executionStatus: 'UNAVAILABLE', executionMessage: '执行服务暂不可用，请稍后查看运行状态。' }); expect(document.body.textContent).toContain('执行服务暂不可用，请稍后查看运行状态。'); expect(document.body.textContent).not.toContain('保存后用于下一轮自动判定') })
})
