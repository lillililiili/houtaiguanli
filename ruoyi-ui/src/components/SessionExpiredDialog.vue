<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { canAccessMenu, firstAccessiblePath } from '@/config/navigation.js';
import { useAuthStore } from '@/stores/auth.js';

// 登录过期时就地重新登录（ZT-29）：不离开当前页面，已填写的表单和弹窗原样保留，重新登录后可以再提交。
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const password = ref('');
const error = ref('');
const busy = ref(false);
const passwordInput = ref();
const visible = computed(() => auth.sessionExpired && Boolean(auth.user));

onMounted(() => { auth.reloginHosts += 1; });
onBeforeUnmount(() => { auth.reloginHosts = Math.max(0, auth.reloginHosts - 1); });
watch(visible, async value => {
  password.value = '';
  error.value = '';
  if (value) { await nextTick(); passwordInput.value?.focus(); }
});

async function relogin() {
  if (busy.value) return;
  if (!password.value) { error.value = '请输入密码'; return; }
  const resubmit = auth.expiredWhileSubmitting;
  busy.value = true;
  error.value = '';
  try {
    const { user, sameUser } = await auth.relogin(password.value);
    password.value = '';
    if (user.must_change_password) await router.replace('/change-password');
    else if (!sameUser) await router.replace(firstAccessiblePath(user));
    else if (route.meta.menuKey && !canAccessMenu(user, route.meta.menuKey)) await router.replace({ path: '/forbidden', query: { page: route.meta.title } });
    else ElMessage.success(resubmit ? '已重新登录，请再提交一次刚才的内容。' : '已重新登录，可以继续操作。');
  } catch (e) {
    error.value = e.message || '重新登录失败，请稍后再试。';
  } finally { busy.value = false; }
}

async function switchAccount() {
  const redirect = route.fullPath;
  auth.clear();
  await router.replace({ path: '/login', query: { redirect } });
}
</script>

<template>
  <el-dialog :model-value="visible" title="登录已过期" width="440px" append-to-body :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" class="session-expired-dialog">
    <el-alert v-if="auth.expiredWhileSubmitting" title="刚才的提交没有保存。" description="页面上已填写的内容都还在，重新登录后请再提交一次。" type="warning" show-icon :closable="false" />
    <el-alert v-else title="为了账号安全，请重新输入密码。" description="当前页面和已填写的内容都还在，重新登录后可以继续操作。" type="info" show-icon :closable="false" />
    <el-form class="session-expired-dialog__form" label-position="top" @submit.prevent>
      <el-form-item label="账号"><el-input :model-value="auth.user?.account || ''" disabled /></el-form-item>
      <el-form-item label="密码" :error="error"><el-input ref="passwordInput" v-model="password" type="password" show-password autocomplete="current-password" placeholder="请输入密码" @keyup.enter="relogin" /></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="switchAccount">换个账号登录</el-button><el-button type="primary" :loading="busy" @click="relogin">重新登录</el-button></template>
  </el-dialog>
</template>

<style scoped>
.session-expired-dialog__form{margin-top:16px}
</style>
