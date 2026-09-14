<script setup>
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth.js';

const auth = useAuthStore();
const router = useRouter();
const formRef = ref();
const loading = ref(false);
const forced = computed(() => auth.mustChangePassword);
const form = reactive({ current: '', password: '', confirm: '' });
const validateConfirm = (_rule, value, callback) => value === form.password ? callback() : callback(new Error('两次输入的新密码不一致'));
const rules = {
  current: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  password: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { min: 6, max: 32, message: '密码长度应为 6–32 位', trigger: 'blur' }],
  confirm: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }]
};
async function submit() {
  await formRef.value.validate(); loading.value = true;
  try { await auth.changePassword(form.current, form.password); ElMessage.success('密码已修改，请重新登录'); await router.replace('/login'); }
  catch (error) { ElMessage.error(error.message); }
  finally { loading.value = false; }
}
</script>

<template>
  <main class="standalone-page"><el-card class="standalone-card">
    <template #header><div><h1>{{ forced ? '首次登录，请修改密码' : '修改密码' }}</h1><p>修改成功后会撤销该账号的全部旧会话。</p></div></template>
    <el-alert v-if="forced" title="当前账号必须先修改初始密码，完成前不能访问其他页面。" type="warning" show-icon :closable="false" />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="当前密码" prop="current"><el-input v-model="form.current" type="password" show-password autocomplete="current-password" /></el-form-item>
      <el-form-item label="新密码" prop="password"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" /></el-form-item>
      <el-form-item label="确认新密码" prop="confirm"><el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" /></el-form-item>
      <div class="form-actions"><el-button v-if="!forced" @click="router.back()">取消</el-button><el-button type="primary" native-type="submit" :loading="loading">确认修改</el-button></div>
    </el-form>
  </el-card></main>
</template>
