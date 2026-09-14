<script setup>
defineProps({ items: { type: Array, default: () => [] } });
</script>

<template>
  <section class="metric-grid" aria-label="关键指标">
    <article v-for="item in items" :key="item.label" class="metric-card" :class="`is-${item.tone || 'blue'}`">
      <span>{{ item.label }}</span><strong>{{ item.value ?? '—' }}</strong><small v-if="item.note">{{ item.note }}</small>
    </article>
  </section>
</template>
