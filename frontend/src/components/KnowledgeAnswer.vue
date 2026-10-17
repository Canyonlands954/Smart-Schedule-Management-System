<script setup lang="ts">
import { ref } from "vue";
import type { KnowledgeReply, KnowledgeSuggestion } from "../domain/knowledge";
import SourceCitations from "./SourceCitations.vue";
import { api } from "../services/api";
const props = defineProps<{
  reply: KnowledgeReply;
  adoptable?: boolean;
  busy?: boolean;
}>();
const emit = defineEmits<{ adopt: [KnowledgeSuggestion] }>();
const feedback = ref(0),
  error = ref("");
async function rate(value: number) {
  try {
    await api(
      "/knowledge/queries/" + props.reply.query_id + "/feedback",
      "POST",
      { value },
    );
    feedback.value = value;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "反馈失败";
  }
}
function name(kind: string) {
  return kind === "duration"
    ? "建议会议时长"
    : kind === "checklist"
      ? "建议准备事项"
      : "建议备注";
}
</script>
<template>
  <div class="knowledge-answer" aria-live="polite">
    <p class="muted">{{ reply.message }}</p>
    <p v-if="reply.warning" class="warning-box">{{ reply.warning }}</p>
    <div
      v-for="(statement, i) in reply.statements"
      :key="i"
      class="knowledge-statement"
    >
      <p>{{ statement.text }}</p>
      <small>依据：{{ statement.quote }}</small>
    </div>
    <div v-if="reply.suggestions.length" class="knowledge-suggestions">
      <h3>供确认的日程建议</h3>
      <div
        v-for="(s, i) in reply.suggestions"
        :key="i"
        class="knowledge-suggestion"
      >
        <div>
          <span class="tag">{{ name(s.kind) }}</span>
          <p>{{ s.value }}{{ s.kind === "duration" ? " 分钟" : "" }}</p>
          <small>原文：{{ s.quote }}</small>
        </div>
        <button
          v-if="adoptable"
          type="button"
          class="small"
          :disabled="busy"
          @click="emit('adopt', s)"
        >
          采用建议
        </button>
      </div>
      <small>采用后仍需核对日程，确认执行时会重新检查冲突。</small>
    </div>
    <SourceCitations :items="reply.citations" />
    <div v-if="reply.mode === 'answer'" class="actions knowledge-feedback">
      <span class="muted">回答是否有帮助？</span
      ><button
        class="small"
        :class="{ primary: feedback === 1 }"
        @click="rate(1)"
      >
        有帮助</button
      ><button
        class="small"
        :class="{ primary: feedback === -1 }"
        @click="rate(-1)"
      >
        需改进</button
      ><span v-if="feedback" class="muted">反馈已记录</span>
    </div>
    <p v-if="error" class="formerror">{{ error }}</p>
  </div>
</template>
