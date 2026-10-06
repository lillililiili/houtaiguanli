import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import MaintenanceWorkflowPanel from '@/views/operations/maintenance/MaintenanceWorkflowPanel.vue'

const granted = new Set()
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: code => granted.has(code) }) }))

let app
let host
const workflow = (state, actions = []) => ({
  state, allowed_actions: actions, events: [], open_incidents: [],
  task: { task_id: 'mt-1', device_name: '融合感知箱 1', device_no: 'BOX-1', reason: '设备离线', reported_at: Date.now() }
})

async function mount(props) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(MaintenanceWorkflowPanel, props) })
  app.use(ElementPlus)
  app.mount(host)
  for (let index = 0; index < 4; index += 1) { await Promise.resolve(); await nextTick() }
}

afterEach(() => {
  app?.unmount()
  host?.remove()
  granted.clear()
})

describe('运维待办权限说明（BUG-02）', () => {
  it('没有设备实时监测操作权限时说明开始处理为何不可用', async () => {
    granted.add('monitoring.read')
    await mount({ workflow: workflow('PENDING') })
    const start = [...host.querySelectorAll('button')].find(item => item.textContent.includes('开始处理'))
    expect(start.disabled).toBe(true)
    expect(host.textContent).toContain('当前账号只能查看这条运维待办')
    expect(host.textContent).toContain('“设备实时监测”的操作权限')
  })

  it('有操作权限或待办已结束时不显示权限说明，按钮仍按后端动作开放', async () => {
    granted.add('monitoring.read')
    granted.add('monitoring.op')
    await mount({ workflow: workflow('PENDING', ['START']) })
    expect(host.textContent).not.toContain('当前账号只能查看这条运维待办')
    const start = [...host.querySelectorAll('button')].find(item => item.textContent.includes('开始处理'))
    expect(start.disabled).toBe(false)
    app.unmount(); host.remove()
    granted.delete('monitoring.op')
    await mount({ workflow: workflow('COMPLETED') })
    expect(host.textContent).not.toContain('当前账号只能查看这条运维待办')
  })
})
