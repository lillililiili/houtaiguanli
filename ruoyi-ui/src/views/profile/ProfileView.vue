<script setup>
import PageHeader from '@/components/PageHeader.vue';
import { useAuthStore } from '@/stores/auth.js';
const auth = useAuthStore();
</script>
<template><section class="page-stack"><PageHeader title="个人资料" description="当前管理端会话与业务前台会话相互独立。" />
  <el-card><el-descriptions :column="2" border>
    <el-descriptions-item label="账号">{{ auth.user?.account || '—' }}</el-descriptions-item>
    <el-descriptions-item label="姓名">{{ auth.user?.name || '—' }}</el-descriptions-item>
    <el-descriptions-item label="电话">{{ auth.user?.phone || '—' }}</el-descriptions-item>
    <el-descriptions-item label="角色">{{ auth.user?.role_name || auth.user?.role_code || '—' }}</el-descriptions-item>
    <el-descriptions-item label="单位">{{ auth.user?.org_name || auth.user?.org_id || '—' }}</el-descriptions-item>
    <el-descriptions-item label="权限版本">{{ auth.user?.permission_version ?? '—' }}</el-descriptions-item>
  </el-descriptions><div class="card-actions"><el-button type="primary" @click="$router.push('/change-password')">修改密码</el-button></div></el-card>
</section></template>
