<script setup>
import { maintenanceTime } from './presentation.js';
const props = defineProps({ reports: { type: Array, default: () => [] }, plans: { type: Array, default: () => [] }, notifications: { type: Array, default: () => [] } });
// 上报记录只带任务内部 ID；按关联任务换成业务编号或名称，查不到时不显示内部 ID（#21）。
const planName = id => { const plan = props.plans.find(item => item.plan_id === id); return plan?.plan_no || plan?.plan_name || '已关联飞行任务'; };
const delivery = value => ({ DELIVERED: '已送达', SUBMITTED: '已提交', PENDING: '待发送', PENDING_DELIVERY: '等待送达', FAILED: '发送失败', NOT_SENT: '未发送', UNKNOWN: '情况未知' })[value] || '情况未知';
const receipt = value => ({ ACKNOWLEDGED: '已确认', RECEIVED: '已签收', PENDING: '等待回执', NOT_EXPECTED: '未要求回执', TIMEOUT: '回执超时', UNKNOWN: '回执未知' })[value] || '回执未记录';
</script>
<template>
  <section v-if="reports.length" class="original-reports"><h4>异常上报记录</h4><article v-for="report in reports" :key="report.report_id"><b>{{ report.actor_name || '系统' }}</b> · {{ maintenanceTime(report.reported_at) }}<p>{{ report.reason }}</p><small v-if="report.plan_id">关联任务：{{ planName(report.plan_id) }}</small></article></section>
  <section v-if="notifications.length" class="original-reports"><h4>历史通知记录</h4><article v-for="(notice,index) in notifications" :key="notice.attempt_id || notice.request_id || index"><b>{{ delivery(notice.delivery_status) }}</b> · {{ receipt(notice.receipt_status) }}<p>{{ maintenanceTime(notice.requested_at) }} · {{ notice.requested_by_name || '未记录' }}</p><p v-if="notice.reason">{{ notice.reason }}</p><p>通知时接收单位：{{ notice.recipient_snapshot?.org_name || notice.recipient_snapshot?.recipient_name || '原记录未保存' }}</p><p v-if="notice.recipient_snapshot?.contact_name">通知时联系人：{{ notice.recipient_snapshot.contact_name }} {{ notice.recipient_snapshot.contact_hint || '' }}</p><p v-if="notice.delivered_at">送达：{{ maintenanceTime(notice.delivered_at) }}</p><p v-if="notice.acknowledged_at">回执：{{ maintenanceTime(notice.acknowledged_at) }}</p><p v-if="notice.receipt_result">{{ notice.receipt_result }}</p><p v-if="notice.blocked_reason">{{ notice.blocked_reason }}</p></article><p class="muted">通知与回执是当时的记录，不代表设备恢复或本次处置办结。</p></section>
</template>
<style scoped>.original-reports article{border-bottom:1px solid #e8edf4;padding:8px 0}.original-reports p{margin:5px 0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.7}.original-reports small{color:#7c8ba0}</style>
