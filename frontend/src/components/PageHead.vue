<script setup lang="ts">
defineProps<{ title: string; description?: string }>();
</script>
<template>
  <div class="pagehead">
    <div>
      <div class="eyebrow">SMART SCHEDULE / 企业协作</div>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div class="actions"><slot /></div>
  </div>
</template>
