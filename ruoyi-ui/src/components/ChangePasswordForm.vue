<script setup>
import { reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth.js';

// 首次登录强制改密和个人资料里的主动改密共用。当前密码输错留在本页标在输入框下，不会退出登录（ZT-28）。
defineProps({ cancelable: { type: Boolean, default: false } });
const emit = defineEmits(['cancel']);
const auth = useAuthStore();
const router = useRouter();
const formRef = ref();
const loading = ref(false);
const form = reactive({ current: '', password: '', confirm: '' });
const errors = reactive({ current: '', password: '', general: '' });

function policyError(value) {
  if (value.length < 6 || value.length > 32) return '新密码需为 6–32 位';
  if (!/\p{Lu}/u.test(value) || !/\p{Ll}/u.test(value) || !/\p{Nd}/u.test(value) || !/[^\p{L}\p{N}]/u.test(value)) return '新密码需包含大写字母、小写字母、数字和特殊字符';
  const account = auth.user?.account || '';
  if (account && value.toLowerCase().includes(account.toLowerCase())) return '新密码不能包含登录账号';
  return '';
}
const validatePassword = (_rule, value, callback) => { const message = policyError(value || ''); return message ? callback(new Error(message)) : callback(); };
const validateConfirm = (_rule, value, callback) => value === form.password ? callback() : callback(new Error('两次输入的新密码不一致'));
const rules = {
  current: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  password: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { validator: validatePassword, trigger: 'blur' }],
  confirm: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }]
};

// 改了对应输入框就收起服务端给的提示。
watch(() => form.current, () => { errors.current = ''; errors.general = ''; });
watch(() => form.password, () => { errors.password = ''; errors.general = ''; });

async function submit() {
  Object.assign(errors, { current: '', password: '', general: '' });
  try { await formRef.value.validate(); } catch { return; }
  // 提交前再按密码规则核一遍，规则提示与服务端一致，不靠失焦校验是否触发。
  const invalid = policyError(form.password);
  if (invalid) { errors.password = invalid; return; }
  if (form.confirm !== form.password) return;
  loading.value = true;
  try {
    await auth.changePassword(form.current, form.password);
    ElMessage.success('密码已修改，请用新密码重新登录');
    await router.replace('/login');
  } catch (error) {
    if (error.code === 'CURRENT_PASSWORD_INCORRECT') errors.current = error.message;
    else if (error.code === 'PASSWORD_POLICY_VIOLATION' || error.code === 'PASSWORD_REUSE') errors.password = error.message;
    // 401 是登录已过期，由重新登录弹窗或登录页说明，这里不重复提示。
    else if (error.status !== 401) errors.general = error.message;
  } finally { loading.value = false; }
}
</script>

<template>
  <el-form ref="formRef" class="change-password-form" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
    <el-alert v-if="errors.general" class="change-password-form__alert" :title="errors.general" type="error" show-icon :closable="false" />
    <el-form-item label="当前密码" prop="current" :error="errors.current"><el-input v-model="form.current" type="password" show-password autocomplete="current-password" /></el-form-item>
    <el-form-item label="新密码" prop="password" :error="errors.password"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" /><small class="change-password-form__hint">6–32 位，包含大写字母、小写字母、数字和特殊字符，不能包含登录账号。</small></el-form-item>
    <el-form-item label="确认新密码" prop="confirm"><el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" /></el-form-item>
    <p class="change-password-form__note">修改成功后，这个账号在所有地方的登录都会失效，需要用新密码重新登录。</p>
    <div class="form-actions"><el-button v-if="cancelable" @click="emit('cancel')">取消</el-button><el-button type="primary" native-type="submit" :loading="loading">确认修改</el-button></div>
  </el-form>
</template>

<style scoped>
.change-password-form__alert{margin-bottom:16px}
.change-password-form__hint{display:block;margin-top:5px;color:var(--admin-muted);line-height:1.5}
.change-password-form__note{margin:0 0 16px;color:var(--admin-muted);font-size:13px}
</style>
