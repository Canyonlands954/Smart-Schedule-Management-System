<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from "vue";
import { storeToRefs } from "pinia";
import { useWorkspace } from "../stores/workspace";
import { api } from "../services/api";
import type { Draft, ScheduleEvent } from "../domain/types";
import PageHead from "../components/PageHead.vue";
import ScheduleEditor from "../components/ScheduleEditor.vue";
import { label } from "../domain/time";
const store = useWorkspace(),
  {
    aiText: text,
    aiDraft: draft,
    aiChat: chat,
    aiConversation: conversation,
    aiRevision: revision,
  } = storeToRefs(store),
  busy = ref(false),
  error = ref("");
interface Reply {
  draft: Draft | null;
  conversation_id: string;
  revision: number;
  assistant_message: string;
  history?: { role: string; content: string }[];
}
const chatlog = ref<HTMLElement | null>(null);
async function scrollChat() {
  await nextTick();
  if (chatlog.value) chatlog.value.scrollTop = chatlog.value.scrollHeight;
}
watch(() => chat.value.length, scrollChat, { flush: "post" });
function remember() {
  sessionStorage.setItem(
    "xushi-ai-conversation",
    JSON.stringify({ id: conversation.value, owner: store.user?.id }),
  );
}
onMounted(async () => {
  await scrollChat();
  if (conversation.value || !store.manager) return;
  try {
    const saved = JSON.parse(
      sessionStorage.getItem("xushi-ai-conversation") ?? "null",
    );
    if (!saved?.id || saved.owner !== store.user?.id) return;
    busy.value = true;
    const r = await api<Reply>("/ai/conversations/" + saved.id);
    conversation.value = r.conversation_id;
    revision.value = r.revision;
    draft.value = r.draft;
    chat.value = r.history ?? [];
  } catch (e) {
    error.value = e instanceof Error ? e.message : "恢复对话失败";
    sessionStorage.removeItem("xushi-ai-conversation");
  } finally {
    busy.value = false;
  }
});
async function parse(
  message = text.value,
  reference?: number,
  recipient?: number,
) {
  if (busy.value || !message.trim()) return;
  busy.value = true;
  error.value = "";
  try {
    const result = await api<Reply>("/ai/parse", "POST", {
      text: message,
      conversation_id: conversation.value || undefined,
      revision: revision.value,
      draft_updates: draft.value ?? undefined,
      reference_event_id: reference,
      recipient_id: recipient,
    });
    chat.value.push(
      { role: "user", content: message },
      { role: "assistant", content: result.assistant_message },
    );
    draft.value = result.draft;
    conversation.value = result.conversation_id;
    revision.value = result.revision;
    remember();
    text.value = "";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "解析失败";
  } finally {
    busy.value = false;
  }
}
function reset() {
  draft.value = null;
  chat.value = [];
  text.value = "";
  conversation.value = "";
  revision.value = 0;
  error.value = "";
  sessionStorage.removeItem("xushi-ai-conversation");
}
function edits(value: Partial<ScheduleEvent>) {
  if (draft.value)
    draft.value = { ...draft.value, ...value, planning: undefined };
}
function sent() {
  const updating = draft.value?.action === "update";
  draft.value = null;
  chat.value.push({
    role: "assistant",
    content: updating
      ? "原日程已修改，接收人将收到更新通知。"
      : "已写入指定接收人的日程；团队组织要求由其协调。你可以继续说“把刚才那场会推迟半小时”。",
  });
}
const questions = computed(() => draft.value?.questions ?? []);
const quickReplies = computed(() => {
  const q = questions.value.join(" "),
    result: string[] = [];
  if (q.includes("哪一天")) result.push("今天", "明天");
  if (q.includes("上午还是下午")) result.push("上午", "下午");
  if (q.includes("持续多久")) result.push("持续30分钟", "持续1小时");
  return result;
});
</script>
<template>
  <PageHead
    title="AI 日程助手"
    description="逐步澄清条件，结合当前安排比较排期，确认后执行"
  />
  <div v-if="!store.manager" class="warning-box">
    此功能需要管理员或团队负责人权限。
  </div>
  <template v-else>
    <div class="ai-steps">
      <span :class="{ current: !draft?.complete }"><b>1</b>对话澄清</span
      ><span :class="{ current: draft?.complete }"><b>2</b>比较与核对</span
      ><span><b>3</b>确认执行</span>
    </div>
    <div class="chat-layout">
      <section class="panel chat">
        <div class="panelhead">
          <h2>安排对话</h2>
          <div class="actions">
            <span class="tag">{{
              store.data?.ai_mode === "model" ? "模型已配置" : "规则解析模式"
            }}</span
            ><button
              type="button"
              class="small"
              :disabled="busy"
              @click="reset"
            >
              新对话
            </button>
          </div>
        </div>
        <div ref="chatlog" class="chatlog" aria-live="polite">
          <div class="sender">日程助手</div>
          <div class="bubble">
            日期、接收人或时长不明确时，我会追问。你可以只补充一个条件，其余信息会保留。修改已有日程时，我会先生成草稿供你确认。
          </div>
          <div v-for="(m, i) in chat" :key="i">
            <div class="sender">
              {{ m.role === "user" ? store.user?.name : "日程助手" }}
            </div>
            <div class="bubble" :class="{ user: m.role === 'user' }">
              {{ m.content }}
            </div>
          </div>
        </div>
        <div v-if="questions.length" class="clarification-box">
          <b>还需要确认</b>
          <p v-for="q in questions" :key="q">{{ q }}</p>
          <div class="quick-replies">
            <button
              v-for="answer in quickReplies"
              :key="answer"
              type="button"
              :disabled="busy"
              @click="parse(answer)"
            >
              {{ answer }}</button
            ><button
              v-for="person in draft?.candidates ?? []"
              :key="person.id"
              type="button"
              :disabled="busy"
              @click="parse('接收人选择：' + person.name, undefined, person.id)"
            >
              {{ person.name }}
            </button>
          </div>
        </div>
        <div v-if="draft?.reference_candidates?.length" class="reference-list">
          <b>请选择要调整的原日程</b
          ><button
            v-for="event in draft.reference_candidates"
            :key="event.id"
            type="button"
            :disabled="busy"
            @click="parse('调整选中的这场安排', event.id)"
          >
            <strong>{{ event.title }} · {{ event.target_name }}</strong
            ><span>{{ label(event.start) }} · {{ event.location }}</span
            ><small v-if="event.pending_reschedule">有待处理改期申请</small>
          </button>
        </div>
        <form class="composer" @submit.prevent="parse()">
          <label class="composer-label" for="ai-message">日程安排内容</label
          ><textarea
            id="ai-message"
            v-model="text"
            required
            maxlength="4000"
            :disabled="busy"
            placeholder="例如：让三组的小赵下午三点到5号会议室开会，带上全组成员"
          />
          <div class="ai-examples">
            <button
              type="button"
              :disabled="busy"
              @click="
                text = '让三组的小赵下午三点到5号会议室开会，带上他们组的所有人'
              "
            >
              填入示例</button
            ><button
              type="button"
              :disabled="busy"
              @click="text = '把刚才那场会推迟半小时'"
            >
              改期示例
            </button>
          </div>
          <div class="composer-bottom">
            <span>草稿保存到当前对话</span
            ><button class="primary" :disabled="busy" :aria-busy="busy">
              {{
                busy ? "正在理解…" : conversation ? "继续对话 →" : "解析安排 →"
              }}
            </button>
          </div>
          <div class="formerror">{{ error }}</div>
        </form>
      </section>
      <section class="panel">
        <div class="panelhead">
          <h2>{{ draft?.action === "update" ? "修改原日程" : "安排预览" }}</h2>
          <span v-if="draft" class="tag">{{
            draft.complete ? "待确认" : "等待补充"
          }}</span>
        </div>
        <div v-if="draft" class="panelbody">
          <div class="parse-evidence">
            <h3>当前业务上下文</h3>
            <p v-for="(value, key) in draft.evidence" :key="key">
              {{ key === "time" && value ? label(value) : value }}
            </p>
            <small
              >只使用权限范围内的人员与安排；只向指定接收人写入日程。</small
            >
          </div>
          <div v-if="draft.action === 'update'" class="success-box">
            正在调整原日程 #{{ draft.id }}，确认后更新原记录，并重新通知接收人。
          </div>
          <ScheduleEditor
            :locked="busy"
            :key="draft.draft_id ?? 'unsaved'"
            :initial="{ ...draft, source: 'ai' }"
            @edits="edits"
            @saved="sent"
          />
        </div>
        <div v-else class="ai-empty">
          <h3>从一句安排开始</h3>
          <p>补齐关键条件后，可以比较人员和会议室都可用的方案。</p>
          <p>已发送安排可继续用“刚才那场会”进行改期。</p>
        </div>
      </section>
    </div>
  </template>
</template>
