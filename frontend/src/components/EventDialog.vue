<script setup lang="ts">
import { actionName } from "../domain/actions";
import { ref, computed, onMounted, onUnmounted, nextTick } from "vue";
import { useWorkspace } from "../stores/workspace";
import { label, local, stamp, status } from "../domain/time";
import { api } from "../services/api";
import ScheduleEditor from "./ScheduleEditor.vue";
import type { HistoryEntry } from "../domain/types";
const store = useWorkspace(),
  box = ref<HTMLElement | null>(null),
  editor = ref<{ dirty: boolean } | null>(null),
  editing = ref(!!store.editor),
  discard = ref(false),
  error = ref(""),
  busy = ref(false),
  op = ref(""),
  report = ref(""),
  reason = ref(""),
  history = ref<HistoryEntry[]>([]),
  start = ref(store.selected ? local(store.selected.start) : ""),
  end = ref(store.selected ? local(store.selected.end) : "");
const event = computed(() => store.selected),
  owner = computed(() => event.value?.target_id === store.user?.id),
  canEdit = computed(() => {
    const e = event.value;
    return (
      !!e &&
      (store.admin ||
        (e.creator_id === store.user?.id &&
          (e.target_id === store.user?.id ||
            (store.manager &&
              store.data?.users.find((u) => u.id === e.target_id)?.team_id ===
                store.user?.team_id))))
    );
  });
const focusBefore = document.activeElement as HTMLElement | null;
function close(force = false) {
  if (!force && editing.value && editor.value?.dirty) {
    discard.value = true;
    return;
  }
  store.selected = null;
  store.editor = null;
  nextTick(() => focusBefore?.focus());
}
function keyboard(e: KeyboardEvent) {
  if (e.key === "Escape") {
    e.preventDefault();
    close();
  }
  if (e.key === "Tab") {
    const elements = box.value?.querySelectorAll<HTMLElement>(
      "button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled])",
    );
    if (!elements?.length) return;
    const first = elements[0],
      last = elements[elements.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }
}
onMounted(() => {
  document.addEventListener("keydown", keyboard);
  box.value?.querySelector<HTMLElement>("button,input")?.focus();
});
onUnmounted(() => document.removeEventListener("keydown", keyboard));
async function action(path: string, data: unknown, method = "POST") {
  if (!event.value || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const id = event.value.id;
    await store.mutate("/events/" + id + path, method, {
      expected_updated_at: event.value.updated_at,
      ...(data as object),
    });
    const updated = store.data?.events.find((e) => e.id === id);
    if (updated) store.selected = updated;
    op.value = "";
    reason.value = "";
    report.value = "";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作失败";
  } finally {
    busy.value = false;
  }
}
async function showHistory() {
  try {
    history.value = (
      await api<{ items: HistoryEntry[] }>(
        "/events/" + event.value?.id + "/history",
      )
    ).items;
    op.value = "history";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取失败";
  }
}
</script>
<template>
  <div
    class="vue-dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby="event-dialog-title"
    @click.self="close()"
  >
    <section ref="box" class="panel">
      <div class="modalhead">
        <h3 id="event-dialog-title">
          {{ editing ? (event ? "编辑日程" : "新建日程") : "日程详情" }}
        </h3>
        <button aria-label="关闭对话框" @click="close()">×</button>
      </div>
      <div v-if="discard" class="warning-box">
        <b>有尚未保存的修改</b>
        <div class="actions">
          <button @click="discard = false">继续编辑</button
          ><button @click="close(true)">放弃修改</button>
        </div>
      </div>
      <ScheduleEditor
        v-if="editing"
        ref="editor"
        :initial="event ?? store.editor ?? {}"
        @saved="close(true)"
      /><template v-else-if="event"
        ><div class="detail-hero">
          <span class="tag" :class="event.status === 'pending' ? 'warn' : ''">{{
            status(event)
          }}</span>
          <h2>{{ event.title }}</h2>
          <p class="detail-time">
            {{ label(event.start) }} — {{ label(event.end) }}
          </p>
          <p>{{ event.location || "地点未指定" }}</p>
        </div>
        <div class="detail-meta-grid">
          <div>
            接收人<strong>{{ event.target_name }}</strong>
          </div>
          <div>
            通知人<strong>{{ event.creator_name }}</strong>
          </div>
        </div>
        <p class="detail-note">{{ event.notes || "暂无补充说明" }}</p>
        <div v-if="event.original_text" class="original-arrangement">
          <details>
            <summary>查看原始安排</summary>
            <p>{{ event.original_text }}</p>
          </details>
        </div>
        <div v-if="event.checklist?.length" class="checklist">
          <div class="checklist-heading">组织与准备</div>
          <label v-for="(item, index) in event.checklist" :key="item.label"
            ><input
              type="checkbox"
              :checked="item.done"
              :disabled="
                busy ||
                !owner ||
                event.status !== 'accepted' ||
                event.phase === 'completed'
              "
              @change="
                action('/checklist', {
                  index,
                  done: ($event.target as HTMLInputElement).checked,
                })
              "
            /><span>{{ item.label }}</span></label
          >
        </div>
        <div v-if="event.completion_report" class="success-box">
          完成反馈：{{ event.completion_report }}
        </div>
        <div v-if="event.reschedule_request" class="warning-box">
          <b>改期申请：{{ event.reschedule_request.status }}</b>
          <p>
            {{ label(event.reschedule_request.start) }} —
            {{ label(event.reschedule_request.end) }}
          </p>
          <p>{{ event.reschedule_request.reason }}</p>
          <p>{{ event.reschedule_request.response }}</p>
          <div
            v-if="event.reschedule_request.status === 'pending'"
            class="actions"
          >
            <template v-if="canEdit"
              ><button @click="action('/reschedule', { action: 'approve' })">
                批准改期</button
              ><button @click="op = 'reject-reschedule'">
                不予改期
              </button></template
            ><button
              v-if="owner"
              @click="action('/reschedule', { action: 'withdraw' })"
            >
              撤回申请
            </button>
          </div>
        </div>
        <div class="flow-toolbar">
          <template v-if="owner && event.status === 'pending'"
            ><button
              class="primary"
              @click="action('/respond', { status: 'accepted' })"
            >
              接受安排</button
            ><button @click="op = 'reject'">拒绝安排</button></template
          ><button
            v-if="
              owner &&
              event.status === 'accepted' &&
              event.phase === 'not_started'
            "
            class="primary"
            @click="action('/progress', { phase: 'in_progress' })"
          >
            开始执行</button
          ><button
            v-if="
              owner &&
              event.status === 'accepted' &&
              event.phase === 'in_progress'
            "
            class="primary"
            @click="op = 'complete'"
          >
            完成安排</button
          ><button
            v-if="
              owner &&
              ['pending', 'accepted'].includes(event.status) &&
              event.phase === 'not_started'
            "
            @click="op = 'reschedule'"
          >
            申请改期</button
          ><button
            v-if="
              canEdit &&
              event.phase !== 'completed' &&
              event.status !== 'cancelled'
            "
            @click="editing = true"
          >
            编辑安排</button
          ><button
            v-if="
              canEdit &&
              event.phase !== 'completed' &&
              event.status !== 'cancelled'
            "
            class="danger"
            @click="op = 'cancel'"
          >
            取消安排</button
          ><button @click="showHistory">变更历史</button>
        </div>
        <form
          v-if="
            [
              'reject',
              'complete',
              'reschedule',
              'reject-reschedule',
              'cancel',
            ].includes(op)
          "
          class="form"
          @submit.prevent="
            op === 'complete'
              ? action('/progress', { phase: 'completed', report })
              : op === 'reschedule'
                ? action('/reschedule', {
                    start: stamp(start),
                    end: stamp(end),
                    reason,
                  })
                : op === 'cancel'
                  ? action('', {}, 'DELETE')
                  : op === 'reject'
                    ? action('/respond', { status: 'rejected', reason })
                    : action('/reschedule', { action: 'reject', reason })
          "
        >
          <div v-if="op === 'reschedule'" class="form-columns">
            <label
              >建议开始<input
                v-model="start"
                type="datetime-local"
                required /></label
            ><label
              >建议结束<input v-model="end" type="datetime-local" required
            /></label>
          </div>
          <label v-if="op === 'complete'"
            >完成反馈<textarea
              v-model="report"
              maxlength="2000"
              required
            /></label
          ><label v-else-if="op !== 'cancel'"
            >{{ op === "reschedule" ? "改期原因" : "回复说明"
            }}<textarea
              v-model="reason"
              maxlength="500"
              :required="op === 'reschedule'"
            />
          </label>
          <p v-if="op === 'cancel'" class="warning-box">
            确认取消这项安排？接收人会收到通知。
          </p>
          <div class="actions">
            <button type="button" @click="op = ''">返回详情</button
            ><button class="primary" :disabled="busy">
              确认{{
                op === "complete" ? "完成" : op === "cancel" ? "取消" : "提交"
              }}
            </button>
          </div>
        </form>
        <div v-if="op === 'history'" class="event-timeline">
          <section v-for="(h, i) in history" :key="i">
            <small
              >{{ label(h.created_at) }} · {{ h.actor_name || "系统" }}</small
            >
            <h3>{{ actionName(h.action) }}</h3>
            <p>
              {{ h.snapshot.title }} · {{ label(h.snapshot.start) }} —
              {{ label(h.snapshot.end) }}
            </p>
            <p>{{ h.snapshot.location }} · {{ status(h.snapshot) }}</p>
          </section>
        </div>
        <div class="formerror" aria-live="polite">{{ error }}</div></template
      >
    </section>
  </div>
</template>
