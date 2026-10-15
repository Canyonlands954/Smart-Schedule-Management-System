<script setup lang="ts">
import { computed, reactive, ref, watch, nextTick } from "vue";
import type { ScheduleEvent, CheckResult, Draft, Plan } from "../domain/types";
import SchedulingPlans from "./SchedulingPlans.vue";
import { local, today, stamp, label } from "../domain/time";
import { api, ApiError } from "../services/api";
import { useWorkspace } from "../stores/workspace";
const props = defineProps<{
    initial?: Partial<ScheduleEvent> & Partial<Draft>;
    locked?: boolean;
  }>(),
  emit = defineEmits<{ saved: []; edits: [Partial<ScheduleEvent>] }>(),
  store = useWorkspace();
const d = props.initial ?? {};
const next = new Date();
next.setMinutes(Math.ceil((next.getMinutes() + 1) / 30) * 30, 0, 0);
const start = d.start ? local(d.start) : d.source === "ai" ? "" : local(next),
  end = d.end
    ? local(d.end)
    : d.source === "ai"
      ? ""
      : local(new Date(new Date(stamp(start)).getTime() + 3600000));
const form = reactive({
  title: d.title ?? "",
  target_id: d.target_id ?? (d.source === "ai" ? 0 : (store.user?.id ?? 0)),
  start,
  end,
  location: d.location ?? "",
  notes: d.notes ?? "",
  priority: d.priority ?? "normal",
  organize_team_id: d.organize_team_id ?? null,
  buffer_before: d.buffer_before ?? 0,
  buffer_after: d.buffer_after ?? 0,
  travel_minutes: d.travel_minutes ?? 0,
  checks: (d.checklist ?? []).map((x) => x.label).join("\n"),
});
const baseline = JSON.stringify(form),
  dirty = computed(() => JSON.stringify(form) !== baseline),
  saving = ref(false),
  error = ref(""),
  result = ref<CheckResult | null>(d.planning ?? null),
  override = ref(false);
const requestId = crypto.randomUUID();
const users = computed(
  () =>
    store.data?.users.filter(
      (u) =>
        u.active &&
        (store.admin ||
          u.id === store.user?.id ||
          (store.manager && u.team_id === store.user?.team_id)),
    ) ?? [],
);
watch(
  form,
  () => {
    result.value = null;
    override.value = false;
    emit("edits", {
      title: form.title,
      target_id: Number(form.target_id),
      start: form.start ? stamp(form.start) : "",
      end: form.end ? stamp(form.end) : "",
      location: form.location,
      notes: form.notes,
      priority: form.priority,
      organize_team_id: form.organize_team_id,
      buffer_before: Number(form.buffer_before),
      buffer_after: Number(form.buffer_after),
      travel_minutes: Number(form.travel_minutes),
      checklist: form.checks
        .split("\n")
        .map((x) => x.trim())
        .filter(Boolean)
        .map((label) => ({ label, done: false })),
    });
  },
  { deep: true },
);
defineExpose({ dirty });
function payload() {
  if (!form.target_id || !form.start || !form.end)
    throw new Error("请先明确接收人、开始时间和结束时间");
  if (new Date(stamp(form.end)) <= new Date(stamp(form.start)))
    throw new Error("结束时间必须晚于开始时间");
  return {
    title: form.title,
    target_id: Number(form.target_id),
    start: stamp(form.start),
    end: stamp(form.end),
    location: form.location,
    notes: form.notes,
    priority: form.priority,
    organize_team_id: form.organize_team_id
      ? Number(form.organize_team_id)
      : null,
    buffer_before: Number(form.buffer_before),
    buffer_after: Number(form.buffer_after),
    travel_minutes: Number(form.travel_minutes),
    checklist: form.checks
      .split("\n")
      .map((x) => x.trim())
      .filter(Boolean),
    expected_updated_at: d.updated_at ?? "",
    request_id: requestId,
    source: d.source ?? "manual",
    original_text: d.original_text ?? "",
    draft_id:
      d.action === "update"
        ? (d.original_draft_id ?? null)
        : (d.draft_id ?? null),
    ai_change_draft_id: d.action === "update" ? d.draft_id : null,
  };
}
async function applyPlan(plan: Plan) {
  form.start = local(plan.start);
  form.end = local(plan.end);
  form.location = plan.location;
  await nextTick();
  await check();
}
async function check() {
  saving.value = true;
  error.value = "";
  try {
    const fingerprint = JSON.stringify(form);
    const checked = await api<CheckResult>("/events/check", "POST", {
      ...payload(),
      exclude_id: d.id ?? 0,
    });
    if (fingerprint !== JSON.stringify(form))
      throw new Error("检查期间安排已更改，请重新检查");
    result.value = checked;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "检查失败";
  } finally {
    saving.value = false;
  }
}
async function submit(keep = false) {
  saving.value = true;
  error.value = "";
  try {
    const data = payload();
    await api(d.id ? "/events/" + d.id : "/events", d.id ? "PUT" : "POST", {
      ...data,
      override_conflicts: keep,
    });
    await store.refresh();
    store.toast(d.id ? "日程已更新" : "安排已发送给指定接收人");
    emit("saved");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "保存失败";
    if (e instanceof ApiError && Array.isArray(e.body.conflicts)) {
      result.value = {
        conflicts: e.body.conflicts,
        suggestions: [],
        warnings: [],
        team_busy: [],
      } as CheckResult;
      override.value = true;
    }
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <form class="form schedule-form" @submit.prevent="submit(false)">
    <fieldset :disabled="saving || locked" class="ai-draft-fields">
      <div class="section-heading">
        <h3>基本信息</h3>
        <small>安排什么、交给谁、何时进行</small>
      </div>
      <label
        >日程标题<input
          v-model="form.title"
          name="title"
          required
          maxlength="100"
          placeholder="例如：三组工作会议" /></label
      ><label
        >接收人<select
          v-model="form.target_id"
          name="target_id"
          aria-label="接收人"
          required
        >
          <option :value="0" disabled>请选择明确的接收人</option>
          <option v-for="u in users" :key="u.id" :value="u.id">
            {{ u.name }} ·
            {{
              store.data?.teams.find((t) => t.id === u.team_id)?.name ??
              "未分组"
            }}
          </option></select
        ><small class="field-help"
          >仅写入这位接收人的日程，团队参会要求由其负责协调。</small
        ></label
      >
      <div class="form-columns">
        <label
          >开始时间<input
            v-model="form.start"
            name="start"
            type="datetime-local"
            required /></label
        ><label
          >结束时间<input
            v-model="form.end"
            name="end"
            type="datetime-local"
            required
        /></label>
      </div>
      <label
        >地点（选填）<input
          v-model="form.location"
          name="location"
          list="rooms"
          maxlength="100"
          placeholder="选择会议室，或填写线上链接 / 其他地点" /><datalist
          id="rooms"
        >
          <option
            v-for="r in store.data?.rooms"
            :key="r.id"
            :value="r.name"
          /></datalist></label
      ><label
        >备注（选填）<textarea
          v-model="form.notes"
          name="notes"
          maxlength="2000"
          placeholder="议题、资料或需提前说明的事项"
        />
      </label>
      <details class="advanced-options">
        <summary>
          组织与准备 <small>优先级、团队、清单与缓冲时间</small>
        </summary>
        <div class="advanced-body">
          <div class="form-columns">
            <label
              >优先级<select v-model="form.priority">
                <option value="normal">普通</option>
                <option value="high">重要</option>
                <option value="urgent">紧急</option>
              </select></label
            ><label
              >组织团队<select v-model="form.organize_team_id">
                <option :value="null">无需组织全组</option>
                <option
                  v-for="t in store.data?.teams"
                  :key="t.id"
                  :value="t.id"
                >
                  {{ t.name }}
                </option>
              </select></label
            >
          </div>
          <label
            >准备清单<textarea
              v-model="form.checks"
              placeholder="每行一项，最多12项"
            />
          </label>
          <div class="constraint-grid">
            <label
              >提前准备（分钟）<input
                v-model="form.buffer_before"
                type="number"
                min="0"
                max="180" /></label
            ><label
              >结束收尾（分钟）<input
                v-model="form.buffer_after"
                type="number"
                min="0"
                max="180" /></label
            ><label
              >通勤（分钟）<input
                v-model="form.travel_minutes"
                type="number"
                min="0"
                max="180"
            /></label>
          </div>
        </div>
      </details>
      <div class="schedule-summary">
        <b>发送前预览</b>
        <p>
          接收人：{{ users.find((u) => u.id === Number(form.target_id))?.name }}
        </p>
        <p>
          {{ form.start.replace("T", " ") }} — {{ form.end.replace("T", " ") }}
        </p>
        <p>{{ form.location || "地点未指定" }}</p>
      </div>
      <div v-if="result" class="check-inline">
        <div :class="result.conflicts.length ? 'warning-box' : 'success-box'">
          {{
            result.conflicts.length
              ? "发现冲突，尚未保存"
              : "未发现人员或会议室冲突"
          }}
          <p v-for="c in result.conflicts" :key="c.event_id ?? c.title">
            {{ c.reason }}：{{ c.title }} · {{ label(c.start) }}
          </p>
        </div>
        <p v-for="w in result.warnings" :key="w" class="muted">{{ w }}</p>
        <SchedulingPlans
          :plans="result.suggestions"
          :disabled="saving"
          @apply="applyPlan"
        />
        <button
          v-if="override"
          type="button"
          class="danger"
          @click="submit(true)"
        >
          确认保留冲突并保存
        </button>
      </div>
      <div class="formerror" aria-live="polite">{{ error }}</div>
      <div class="form-footer">
        <small>提交时自动检查人员、会议室与组织要求。</small>
        <div class="actions form-actions">
          <button type="button" @click="check">检查冲突与推荐排期</button
          ><button class="primary" :aria-busy="saving">
            {{
              d.action === "update"
                ? "确认修改原日程"
                : d.id
                  ? "保存修改"
                  : d.source === "ai"
                    ? "确认并发送"
                    : "创建日程"
            }}
          </button>
        </div>
      </div>
    </fieldset>
  </form>
</template>
