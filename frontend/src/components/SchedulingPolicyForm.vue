<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api } from "../services/api";
import { useWorkspace } from "../stores/workspace";
import type { SchedulingPolicy } from "../domain/types";
const store = useWorkspace(),
  policy = ref<SchedulingPolicy | null>(null),
  holidays = ref(""),
  workdays = ref(""),
  busy = ref(false),
  error = ref("");
const days = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
onMounted(async () => {
  try {
    policy.value = (
      await api<{ policy: SchedulingPolicy }>("/scheduling-policy")
    ).policy;
    holidays.value = policy.value.holidays.join("\n");
    workdays.value = policy.value.workdays.join("\n");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取失败";
  }
});
async function save() {
  if (!policy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const dates = (value: string) =>
      value
        .split(/[\n,，]/)
        .map((x) => x.trim())
        .filter(Boolean);
    policy.value = (
      await api<{ policy: SchedulingPolicy }>("/scheduling-policy", "PUT", {
        ...policy.value,
        holidays: dates(holidays.value),
        workdays: dates(workdays.value),
      })
    ).policy;
    store.toast("排期规则已保存");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "保存失败";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="panel section-gap">
    <div class="panelhead">
      <h2>智能排期规则</h2>
      <span class="tag">北京时间</span>
    </div>
    <div class="panelbody">
      <p class="muted">
        候选方案遵循工作日和工作时段；个人准备、收尾及通勤时间在每项安排中设置。
      </p>
      <form v-if="policy" class="form" @submit.prevent="save">
        <fieldset :disabled="busy || !store.admin" class="ai-draft-fields">
          <div class="form-columns">
            <label
              >工作开始<input
                v-model="policy.work_start"
                type="time"
                required /></label
            ><label
              >工作结束<input v-model="policy.work_end" type="time" required
            /></label>
          </div>
          <div class="weekday-options">
            <label v-for="(day, i) in days" :key="day"
              ><input
                v-model="policy.weekdays"
                type="checkbox"
                :value="i + 1"
              />{{ day }}</label
            >
          </div>
          <div class="form-columns">
            <label
              >候选间隔<select v-model.number="policy.step_minutes">
                <option :value="15">15分钟</option>
                <option :value="30">30分钟</option>
                <option :value="60">60分钟</option>
              </select></label
            ><label
              >向后搜索范围（天）<input
                v-model.number="policy.horizon_days"
                type="number"
                min="1"
                max="14"
                required
            /></label>
          </div>
          <div class="form-columns">
            <label
              >休息日例外<textarea
                v-model="holidays"
                placeholder="每行一个日期，例如 2026-10-01"
              /></label
            ><label
              >补班日例外<textarea
                v-model="workdays"
                placeholder="每行一个日期，例如 2026-10-10"
              />
            </label>
          </div>
          <button v-if="store.admin" class="primary" :disabled="busy">
            保存排期规则
          </button>
        </fieldset>
      </form>
      <div class="formerror">{{ error }}</div>
    </div>
  </section>
</template>
