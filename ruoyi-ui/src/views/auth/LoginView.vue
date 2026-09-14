<script setup>
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Lock, User } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { firstAccessiblePath } from '@/config/navigation.js';
import { safeRedirect } from '@/router/index.js';
import { useAuthStore } from '@/stores/auth.js';

const ACCOUNT_KEY = 'uav.admin.account.v1';
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const loading = ref(false);
const form = reactive({ account: localStorage.getItem(ACCOUNT_KEY) || '', password: '', remember: Boolean(localStorage.getItem(ACCOUNT_KEY)) });
const rules = { account: [{ required: true, message: '请输入账号', trigger: 'blur' }], password: [{ required: true, message: '请输入密码', trigger: 'blur' }] };
const formRef = ref();

async function submit() {
  await formRef.value.validate();
  loading.value = true;
  try {
    const user = await auth.login({ account: form.account.trim(), password: form.password });
    if (form.remember) localStorage.setItem(ACCOUNT_KEY, form.account.trim()); else localStorage.removeItem(ACCOUNT_KEY);
    ElMessage.success('登录成功');
    await router.replace(user.must_change_password ? '/change-password' : safeRedirect(route.query.redirect, user) || firstAccessiblePath(user));
  } catch (error) { ElMessage.error(error.message); }
  finally { loading.value = false; }
}
</script>

<template>
  <main class="login-page">
    <section class="login-hero" aria-label="平台介绍">
      <div class="login-grid" />
      <div class="login-hero__content">
        <span class="login-eyebrow">LOW-ALTITUDE SAFETY · ADMIN</span>
        <h1>无人机融合感知与<br />低空安全管理平台</h1>
        <p>统一维护设备接入、运行状态、账号权限与审计证据，让每一次配置和操作都有据可查。</p>
        <div class="login-signals"><span>单一数据源</span><span>最小权限</span><span>全程审计</span></div>
      </div>
    </section>
    <section class="login-panel">
      <div class="login-card">
        <header><span class="brand__mark">U</span><div><h2>后台管理系统</h2><p>请使用平台账号登录</p></div></header>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent="submit">
          <el-form-item label="账号" prop="account"><el-input v-model="form.account" autocomplete="username" placeholder="请输入账号" :prefix-icon="User" /></el-form-item>
          <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" show-password autocomplete="current-password" placeholder="请输入密码" :prefix-icon="Lock" @keyup.enter="submit" /></el-form-item>
          <div class="login-options"><el-checkbox v-model="form.remember">记住账号</el-checkbox><span>忘记密码请联系系统管理员</span></div>
          <el-button native-type="submit" type="primary" :loading="loading" class="login-submit">登录</el-button>
        </el-form>
        <footer>管理端与业务前台共用账号和权限数据，会话相互独立。</footer>
      </div>
    </section>
  </main>
</template>
