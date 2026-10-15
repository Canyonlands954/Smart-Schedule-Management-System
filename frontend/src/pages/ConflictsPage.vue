<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useWorkspace } from "../stores/workspace";
import { api } from "../services/api";
import type { ScheduleEvent, Conflict } from "../domain/types";
import { label } from "../domain/time";
import PageHead from "../components/PageHead.vue";
const store = useWorkspace(),
  data = ref<{
    pairs: { event: ScheduleEvent; conflict: Conflict }[];
    advisories: { event: ScheduleEvent; warnings: string[] }[];
  }>({ pairs: [], advisories: [] }),
  error = ref("");
async function load() {
  try {
    data.value = await api("/conflict-report");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取失败";
  }
}
onMounted(load);
</script>
<template>
  <PageHead title="冲突管理" description="看清时间与资源冲突，协调后再做安排"
    ><button @click="load">刷新检查</button></PageHead
  >
  <div v-for="(p, i) in data.pairs" :key="i" class="panel conflictitem">
    <div class="conflicttop">
      <h2>{{ p.conflict.reason }}</h2>
      <span class="tag warn">需要协调</span>
    </div>
    <div class="conflictcompare">
      <div class="conflictevent">
        <b>{{ p.event.title }}</b>
        <p>{{ p.event.target_name }} · {{ label(p.event.start) }}</p>
      </div>
      <span>↔</span>
      <div class="conflictevent">
        <b>{{ p.conflict.title }}</b>
        <p>{{ p.conflict.target_name }} · {{ label(p.conflict.start) }}</p>
      </div>
    </div>
    <button @click="store.open(p.event)">查看并协调</button>
  </div>
  <section v-if="!data.pairs.length" class="panel">
    <div class="empty">当前可见安排未发现冲突</div>
  </section>
  <div
    v-for="(a, i) in data.advisories"
    :key="i"
    class="deep-conflict section-gap"
  >
    <h3>{{ a.event.title }}</h3>
    <p v-for="w in a.warnings" :key="w">{{ w }}</p>
    <button class="small" @click="store.open(a.event)">查看安排</button>
  </div>
  <div class="formerror">{{ error }}</div>
</template>
