<script setup lang="ts">
import type { Plan } from "../domain/types";
import { label } from "../domain/time";
defineProps<{ plans: Plan[]; disabled?: boolean }>();
defineEmits<{ apply: [Plan] }>();
</script>
<template>
  <div v-if="plans.length" class="planning-options">
    <div class="section-heading">
      <h3>候选排期方案</h3>
      <small>综合等待时间、换会议室和团队冲突，按代价由低到高排序</small>
    </div>
    <article
      v-for="plan in plans"
      :key="plan.start + plan.location"
      class="planning-card"
    >
      <div class="planning-title">
        <b
          >方案 {{ plan.rank }}
          <span v-if="plan.rank === 1" class="tag">优先推荐</span></b
        ><span class="muted">团队冲突 {{ plan.team_busy_count }} 人</span>
      </div>
      <p>
        <strong>{{ label(plan.start) }}</strong> — {{ label(plan.end) }}
      </p>
      <p>{{ plan.location || "地点未指定" }}</p>
      <ul>
        <li v-for="reason in plan.reasons" :key="reason">{{ reason }}</li>
      </ul>
      <button type="button" :disabled="disabled" @click="$emit('apply', plan)">
        选用方案 {{ plan.rank }} 并重新检查
      </button>
    </article>
    <small class="muted">仅根据平台内已有安排比较，选用后仍需确认保存。</small>
  </div>
</template>
