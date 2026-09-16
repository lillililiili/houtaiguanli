<script setup>
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { firstAccessiblePath } from '@/config/navigation.js';
import { safeRedirect } from '@/router/index.js';
import { useAuthStore } from '@/stores/auth.js';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const loading = ref(false);
const hint = ref('');

async function retry() {
  loading.value = true;
  hint.value = '';
  try {
    await auth.restore();
    if (auth.authenticated) {
      await router.replace(safeRedirect(route.query.from, auth.user) || firstAccessiblePath(auth.user));
      return;
    }
    hint.value = auth.restoreError || '仍无法连接后台服务。请确认 API 已启动后再试。';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <main class="service-down" role="alert">
    <header class="service-down__brand">
      <img src="/assets/img/brand/logo-mark.png" alt="" width="1251" height="559" />
      <span>LOW-ALTITUDE SAFETY · ADMIN</span>
    </header>
    <section class="service-down__card">
      <div class="service-down__pulse" aria-hidden="true"><i /></div>
      <p class="service-down__kicker">API 链路中断</p>
      <h1>后台服务暂不可用</h1>
      <p class="service-down__lead">管理端连不上平台接口。当前登录会话还在，服务恢复后点「重新连接」即可继续，不必重新登录。</p>
      <ul class="service-down__facts">
        <li>确认本机 API 已启动（默认端口 8080）</li>
        <li>会话尚未清除，重连后可回到刚才的页面</li>
      </ul>
      <p v-if="hint" class="service-down__hint">{{ hint }}</p>
      <div class="service-down__actions">
        <el-button type="primary" :loading="loading" @click="retry">重新连接</el-button>
        <el-button @click="$router.push('/login')">返回登录页</el-button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.service-down {
  position: relative;
  display: grid;
  min-height: 100vh;
  padding: 28px 22px 40px;
  place-items: center;
  overflow: auto;
  isolation: isolate;
  background: #061a32 url('/assets/img/admin/login-aerial.webp') center / cover no-repeat;
}
.service-down::before {
  position: fixed;
  z-index: -1;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(180deg, rgba(4, 18, 39, .72), rgba(5, 22, 44, .58) 46%, rgba(3, 14, 31, .78));
  content: "";
}
.service-down__brand {
  position: absolute;
  top: 26px;
  left: 32px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: #d7e9f9;
  font: 700 12px/1 Bahnschrift, "Segoe UI", sans-serif;
  letter-spacing: .16em;
}
.service-down__brand img {
  width: 54px;
  height: auto;
  filter: drop-shadow(0 8px 18px rgba(0, 33, 76, .28));
}
.service-down__card {
  position: relative;
  width: min(460px, 100%);
  padding: 36px 32px 30px;
  border: 1px solid rgba(255, 255, 255, .68);
  border-radius: 18px;
  background: rgba(250, 253, 255, .92);
  box-shadow: 0 30px 86px rgba(1, 21, 47, .31), inset 0 1px 0 rgba(255, 255, 255, .82);
  backdrop-filter: blur(22px) saturate(125%);
  text-align: center;
}
.service-down__card::before {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  height: 3px;
  background: linear-gradient(90deg, var(--admin-primary), var(--admin-secondary));
  content: "";
}
.service-down__pulse {
  display: grid;
  width: 72px;
  height: 72px;
  margin: 4px auto 18px;
  place-items: center;
  border: 1px solid #acd0f9;
  border-radius: 50%;
  background: linear-gradient(145deg, #eef7ff, #dceeff);
  box-shadow: 0 12px 30px rgba(22, 119, 255, .12), inset 0 1px 0 #fff;
}
.service-down__pulse i {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--admin-primary);
  box-shadow: 0 0 0 0 rgba(22, 119, 255, .45);
  animation: service-down-ping 1.8s ease-out infinite;
}
.service-down__kicker {
  margin: 0 0 8px;
  color: var(--admin-accent);
  font: 700 11px/1.4 Bahnschrift, "Segoe UI", sans-serif;
  letter-spacing: .18em;
}
.service-down h1 {
  margin: 0 0 10px;
  color: var(--admin-text-strong);
  font-size: 26px;
  font-weight: 750;
  letter-spacing: .02em;
}
.service-down__lead {
  margin: 0 0 18px;
  color: var(--admin-muted);
  line-height: 1.75;
}
.service-down__facts {
  margin: 0 0 22px;
  padding: 12px 16px;
  list-style: none;
  border: 1px solid var(--admin-border);
  border-radius: 12px;
  background: #f8fbff;
  color: #405069;
  text-align: left;
  line-height: 1.7;
}
.service-down__facts li { position: relative; padding-left: 16px; }
.service-down__facts li + li { margin-top: 6px; }
.service-down__facts li::before {
  position: absolute;
  top: .62em;
  left: 2px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--admin-secondary);
  content: "";
}
.service-down__hint {
  margin: -6px 0 16px;
  color: var(--admin-danger);
  line-height: 1.6;
}
.service-down__actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 10px; }
.service-down__actions .el-button { min-width: 128px; min-height: 42px; }
@keyframes service-down-ping {
  0% { box-shadow: 0 0 0 0 rgba(22, 119, 255, .45); }
  70% { box-shadow: 0 0 0 14px rgba(22, 119, 255, 0); }
  100% { box-shadow: 0 0 0 0 rgba(22, 119, 255, 0); }
}
@media (max-width: 720px) {
  .service-down__brand { position: static; margin-bottom: 22px; }
  .service-down__card { padding: 28px 20px 24px; }
}
@media (prefers-reduced-motion: reduce) {
  .service-down__pulse i { animation: none; }
}
</style>
