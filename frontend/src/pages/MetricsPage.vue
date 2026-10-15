<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useWorkspace } from "../stores/workspace";
import { api } from "../services/api";
import PageHead from "../components/PageHead.vue";
const store = useWorkspace(),
  data = ref<Record<string, number | null>>({}),
  error = ref("");
const metrics = [
  ["event_count", "实际业务日程"],
  ["completed", "已完成安排"],
  ["pending", "待确认安排"],
  ["parse_count", "实际解析样本"],
  ["sent_drafts", "已发送草稿"],
  ["corrected_drafts", "发送前修改"],
  ["average_confirmation_minutes", "平均确认耗时（分钟）"],
  ["confirmation_samples", "确认耗时样本"],
  ["rule_samples", "规则解析样本"],
  ["model_samples", "模型解析样本"],
];
async function load() {
  if (!store.admin) return;
  try {
    data.value = await api("/metrics");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取失败";
  }
}
onMounted(load);
</script>
<template>
  <PageHead
    title="协作分析"
    description="按实际业务记录观察协作，模拟数据单独展示"
    ><button @click="load">更新统计</button></PageHead
  >
  <div v-if="!store.admin" class="warning-box">此功能需要管理员权限。</div>
  <template v-else
    ><div class="stats">
      <div v-for="m in metrics" :key="m[0]" class="stat">
        <div class="stat-label">{{ m[1] }}</div>
        <strong>{{ data[m[0]] ?? "暂无样本" }}</strong>
      </div>
    </div>
    <section class="panel">
      <div class="panelhead"><h2>数据口径</h2></div>
      <div class="panelbody metric-notes">
        <p>
          模拟日程 {{ data.demo_event_count ?? 0 }} 项，模拟解析
          {{ data.demo_parse_count ?? 0 }} 次，模拟完成
          {{ data.demo_completed ?? 0 }} 项。
        </p>
        <p>
          实际业务统计排除模拟样本，规则与模型解析分别计数。字段齐全不等于解析准确率；确认耗时按每轮派发到首次回复计算。
        </p>
      </div>
    </section></template
  >
  <div class="formerror">{{ error }}</div>
</template>
