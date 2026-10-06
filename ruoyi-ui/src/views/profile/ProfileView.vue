<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import PageHeader from '@/components/PageHeader.vue';
import ChangePasswordForm from '@/components/ChangePasswordForm.vue';
import { useAuthStore } from '@/stores/auth.js';
import { dataScopeLabel } from '@/utils/dataScope.js';

// 本人能改的只有姓名和联系电话；单位、角色、数据范围由系统管理员在用户管理里调整（ZT-28）。
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const formRef = ref();
const saving = ref(false);
const saveError = ref('');
const form = reactive({ name: '', phone: '' });
const activeTab = ref(tabOf(route.query.section));
const rules = {
  name: [{ required: true, whitespace: true, message: '请输入姓名', trigger: 'blur' }, { max: 64, message: '姓名不能超过 64 个字', trigger: 'blur' }],
  phone: [{ max: 32, message: '联系电话不能超过 32 位', trigger: 'blur' }, { pattern: /^[0-9+()\- ]*$/, message: '联系电话只能填写数字、空格和 + - ( )', trigger: 'blur' }]
};
const changed = computed(() => form.name.trim() !== (auth.user?.name || '') || form.phone.trim() !== (auth.user?.phone || ''));

function tabOf(section) { return section === 'password' ? 'password' : 'basic'; }
function fill(user) { form.name = user?.name || ''; form.phone = user?.phone || ''; saveError.value = ''; }
// 同一账号的资料刷新（例如登录过期后重新登录）不覆盖正在填写的内容，换了账号才重新带入。
watch(() => auth.user?.user_id, () => fill(auth.user), { immediate: true });
watch(() => route.query.section, section => { activeTab.value = tabOf(section); });
function selectTab(name) { router.replace({ query: name === 'password' ? { section: 'password' } : {} }); }

async function save() {
  saveError.value = '';
  try { await formRef.value.validate(); } catch { return; }
  saving.value = true;
  try {
    fill(await auth.updateProfile({ name: form.name.trim(), phone: form.phone.trim() }));
    ElMessage.success('个人资料已保存');
  } catch (error) {
    if (error.code === 'VERSION_CONFLICT') {
      saveError.value = '资料刚被其他操作修改过，已读取最新资料；你填写的内容还在，请核对后再保存。';
      try { await auth.loadCurrentUser(); } catch { /* 读不到最新资料时保留提示，稍后再试 */ }
    // 401 是登录已过期，由重新登录弹窗说明；填写的内容保留，重新登录后再保存。
    } else if (error.status !== 401) saveError.value = error.message;
  } finally { saving.value = false; }
}
</script>

<template><section class="page-stack"><PageHeader title="个人资料" description="可以修改自己的姓名、联系电话和登录密码。管理端与业务前台会话相互独立。" />
  <el-card class="profile-card">
    <el-tabs v-model="activeTab" @tab-change="selectTab">
      <el-tab-pane label="基本资料" name="basic">
        <el-form ref="formRef" class="profile-form" :model="form" :rules="rules" label-position="top" @submit.prevent="save">
          <el-alert v-if="saveError" class="profile-form__alert" :title="saveError" type="error" show-icon :closable="false" />
          <div class="profile-grid">
            <el-form-item label="账号"><el-input :model-value="auth.user?.account || ''" disabled /></el-form-item>
            <el-form-item label="姓名" prop="name"><el-input v-model="form.name" maxlength="64" autocomplete="name" /></el-form-item>
            <el-form-item label="联系电话" prop="phone"><el-input v-model="form.phone" maxlength="32" autocomplete="tel" placeholder="选填" /></el-form-item>
            <el-form-item label="角色"><el-input :model-value="auth.user?.role_name || auth.user?.role_code || '—'" disabled /></el-form-item>
            <el-form-item label="所属单位"><el-input :model-value="auth.user?.org_name || '—'" disabled /></el-form-item>
            <el-form-item label="数据范围"><el-input :model-value="dataScopeLabel(auth.user?.data_scope)" disabled /></el-form-item>
          </div>
          <p class="profile-form__note">所属单位、角色和数据范围由系统管理员在用户管理中调整。</p>
          <div class="form-actions"><el-button :disabled="!changed || saving" @click="fill(auth.user)">恢复原值</el-button><el-button type="primary" native-type="submit" :loading="saving" :disabled="!changed">保存</el-button></div>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="修改密码" name="password">
        <div class="profile-password"><ChangePasswordForm /></div>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</section></template>

<style scoped>
.profile-form{max-width:720px}.profile-password{max-width:460px}
.profile-form__alert{margin-bottom:16px}
.profile-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 18px}
.profile-form__note{margin:0 0 16px;color:var(--admin-muted);font-size:13px}
@media(max-width:700px){.profile-grid{grid-template-columns:1fr}}
</style>
