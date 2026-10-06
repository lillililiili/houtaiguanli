<script setup>
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import ChangePasswordForm from '@/components/ChangePasswordForm.vue';
import { useAuthStore } from '@/stores/auth.js';

// 首次登录或密码被重置后的强制改密页；平时主动改密在“个人资料 → 修改密码”。
const auth = useAuthStore();
const router = useRouter();
const forced = computed(() => auth.mustChangePassword);
</script>

<template>
  <main class="standalone-page"><div class="standalone-brand" aria-hidden="true"><img src="/assets/img/brand/logo-mark.png" alt="" width="1251" height="559" /><span>低空安全管理</span></div><el-card class="standalone-card">
    <template #header><div><h1>{{ forced ? '首次登录，请修改密码' : '修改密码' }}</h1><p>修改成功后会撤销该账号的全部旧会话。</p></div></template>
    <el-alert v-if="forced" title="当前账号必须先修改初始密码，完成前不能访问其他页面。" type="warning" show-icon :closable="false" />
    <ChangePasswordForm :cancelable="!forced" @cancel="router.back()" />
  </el-card></main>
</template>
