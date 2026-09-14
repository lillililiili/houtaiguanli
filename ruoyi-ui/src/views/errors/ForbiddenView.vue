<script setup>import { useRoute } from 'vue-router'; const route=useRoute();</script>
<template><section class="result-page"><div class="result-icon">403</div><h1>无权访问{{ route.query.page ? `“${route.query.page}”` : '该页面' }}</h1><p>菜单权限和操作权限均由后端会话提供。请联系超级管理员调整角色权限。</p><el-button type="primary" @click="$router.push('/')">返回后台首页</el-button></section></template>
