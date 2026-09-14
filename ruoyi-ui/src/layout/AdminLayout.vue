<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowDown, Expand, Fold, SwitchButton, User } from '@element-plus/icons-vue';
import { navigationGroups } from '@/config/navigation.js';
import { useAuthStore } from '@/stores/auth.js';
import { useTagsStore } from '@/stores/tags.js';

const auth = useAuthStore();
const tags = useTagsStore();
const route = useRoute();
const router = useRouter();
const collapsed = ref(false);
const mobileOpen = ref(false);

const visibleGroups = computed(() => navigationGroups.map(group => ({
  ...group,
  children: group.children.filter(item => auth.hasMenu(item.key) && auth.hasPermission(item.permission))
})).filter(group => group.children.length));

const breadcrumbs = computed(() => {
  const item = navigationGroups.flatMap(group => group.children.map(child => ({ group: group.title, ...child }))).find(value => value.path === route.path);
  return item ? [item.group, item.title] : [route.meta.title || '后台管理'];
});

watch(() => route.fullPath, () => { tags.visit(route); mobileOpen.value = false; }, { immediate: true });

function resize() {
  if (window.innerWidth < 900) collapsed.value = false;
  else mobileOpen.value = false;
}
onMounted(() => window.addEventListener('resize', resize));
onBeforeUnmount(() => window.removeEventListener('resize', resize));

async function userCommand(command) {
  if (command === 'logout') { await auth.logout(); await router.replace('/login'); }
  else await router.push(command);
}

function closeTag(item) {
  tags.close(item.basePath);
  if (route.path === item.basePath) router.push(tags.visited.at(-1)?.path || '/');
}
</script>

<template>
  <div class="admin-shell" :class="{ 'is-collapsed': collapsed, 'is-mobile-open': mobileOpen }">
    <aside class="admin-sidebar" aria-label="后台主导航">
      <div class="brand">
        <span class="brand__mark">U</span>
        <span v-if="!collapsed" class="brand__text"><b>低空安全管理</b><small>ADMIN CONSOLE</small></span>
      </div>
      <el-scrollbar class="sidebar-scroll">
        <el-menu :default-active="route.path" :collapse="collapsed" router unique-opened>
          <el-sub-menu v-for="group in visibleGroups" :key="group.key" :index="group.key" :aria-label="group.title">
            <template #title><el-icon><component :is="group.icon" /></el-icon><span>{{ group.title }}</span></template>
            <el-menu-item v-for="item in group.children" :key="item.path" :index="item.path" :aria-label="item.title">
              <el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.title }}</template>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-scrollbar>
      <button class="sidebar-toggle" type="button" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" @click="collapsed=!collapsed">
        <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon><span v-if="!collapsed">收起侧栏</span>
      </button>
    </aside>
    <button v-if="mobileOpen" type="button" class="mobile-mask" aria-label="关闭导航" @click="mobileOpen=false" />

    <section class="admin-main">
      <header class="admin-topbar">
        <button type="button" class="mobile-menu" aria-label="打开导航" @click="mobileOpen=true"><el-icon><Expand /></el-icon></button>
        <el-breadcrumb separator="/"><el-breadcrumb-item v-for="item in breadcrumbs" :key="item">{{ item }}</el-breadcrumb-item></el-breadcrumb>
        <div class="topbar-spacer" />
        <el-dropdown trigger="click" @command="userCommand">
          <button type="button" class="user-trigger">
            <span class="user-avatar">{{ (auth.user?.name || auth.user?.account || '管').slice(0, 1) }}</span>
            <span class="user-copy"><b>{{ auth.user?.name || auth.user?.account }}</b><small>{{ auth.user?.role_name || '平台账号' }}</small></span>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="/profile" :icon="User">个人资料</el-dropdown-item>
            <el-dropdown-item divided command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </header>

      <nav class="tags-view" aria-label="已访问页面">
        <span v-for="item in tags.visited" :key="item.basePath" class="tag-item" :class="{ active: item.basePath===route.path }">
          <button class="tag-link" type="button" @click="router.push(item.path)">{{ item.title }}</button>
          <button v-if="!item.affix" class="tag-close" type="button" :aria-label="`关闭${item.title}标签`" @click="closeTag(item)">×</button>
        </span>
      </nav>

      <main class="admin-content"><router-view /></main>
    </section>
  </div>
</template>
