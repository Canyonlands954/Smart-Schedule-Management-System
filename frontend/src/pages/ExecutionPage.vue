<script setup lang="ts">
import { ref, computed } from "vue";
import { useWorkspace } from "../stores/workspace";
import { label } from "../domain/time";
import PageHead from "../components/PageHead.vue";
const store = useWorkspace(),
  query = ref(""),
  member = ref("all");
const phases = [
  ["pending", "待确认"],
  ["not_started", "待执行"],
  ["in_progress", "进行中"],
  ["completed", "已完成"],
];
const events = computed(
  () =>
    store.data?.events.filter(
      (e) =>
        (e.title + e.target_name).includes(query.value) &&
        (member.value === "all" || e.target_id === Number(member.value)),
    ) ?? [],
);
function column(phase: string) {
  return events.value.filter((e) =>
    phase === "pending"
      ? e.status === "pending"
      : e.status === "accepted" && e.phase === phase,
  );
}
</script>
<template>
  <PageHead title="执行看板" description="让安排有回应，让工作有结果"
    ><button class="primary" @click="store.create()">
      ＋ 新建日程
    </button></PageHead
  >
  <div class="filterbar">
    <input
      v-model="query"
      placeholder="查找安排或成员"
      aria-label="查找执行安排"
    /><select v-model="member" aria-label="筛选执行成员">
      <option value="all">全部可见成员</option>
      <option v-for="u in store.data?.users" :key="u.id" :value="u.id">
        {{ u.name }}
      </option></select
    ><small>确认 → 准备 → 执行 → 反馈</small>
  </div>
  <div class="execution-grid">
    <section
      v-for="(p, i) in phases"
      :key="p[0]"
      class="execution-column"
      :class="'phase-' + i"
    >
      <div class="execution-heading">
        <h3><span class="phase-dot" />{{ p[1] }}</h3>
        <span>{{ column(p[0]).length }}</span>
      </div>
      <button
        v-for="e in column(p[0])"
        :key="e.id"
        class="execution-card"
        @click="store.open(e)"
      >
        <span class="tag gray">{{
          e.priority === "urgent"
            ? "紧急"
            : e.priority === "high"
              ? "重要"
              : "普通"
        }}</span
        ><b>{{ e.title }}</b>
        <p>{{ label(e.start) }}</p>
        <p>{{ e.location }}</p>
        <div v-if="e.checklist.length" class="card-progress">
          <span>准备进度</span
          ><span
            >{{ e.checklist.filter((c) => c.done).length }}/{{
              e.checklist.length
            }}</span
          ><progress
            :value="e.checklist.filter((c) => c.done).length"
            :max="e.checklist.length"
          />
        </div>
        <div class="card-member">
          <span class="avatar">{{ e.target_name[0] }}</span
          >{{ e.target_name }}
        </div>
      </button>
      <div v-if="!column(p[0]).length" class="mini-empty muted">暂无安排</div>
    </section>
  </div>
</template>
