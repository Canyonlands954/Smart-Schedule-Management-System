<script setup lang="ts">
import { ref, nextTick } from "vue";
import { api } from "../services/api";
import type { Citation } from "../domain/knowledge";
defineProps<{ items: Citation[] }>();
const dialog = ref<HTMLDialogElement | null>(null),
  selected = ref<Citation | null>(null),
  busy = ref(false),
  error = ref("");
async function open(c: Citation) {
  selected.value = null;
  error.value = "";
  busy.value = true;
  await nextTick();
  dialog.value?.showModal();
  try {
    selected.value = await api<Citation>("/knowledge/chunks/" + c.id);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "无法读取引用";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div v-if="items.length" class="knowledge-sources" aria-label="资料来源">
    <button
      v-for="c in items"
      :key="c.id"
      type="button"
      class="source-card"
      @click="open(c)"
    >
      <span class="source-marker">文</span
      ><span
        ><strong>{{ c.title }}</strong
        ><small
          >{{ c.base_name }} · v{{ c.version }} · {{ c.locator }}</small
        ></span
      ><span aria-hidden="true">↗</span>
    </button>
  </div>
  <dialog ref="dialog" class="knowledge-dialog">
    <div class="modalhead">
      <h3>来源核对</h3>
      <button type="button" aria-label="关闭来源预览" @click="dialog?.close()">
        ×
      </button>
    </div>
    <p v-if="busy" role="status">正在核对访问权限…</p>
    <p v-if="error" class="formerror" role="alert">{{ error }}</p>
    <template v-if="selected"
      ><h3>{{ selected.title }}</h3>
      <p class="muted">v{{ selected.version }} · {{ selected.locator }}</p>
      <blockquote class="knowledge-excerpt">{{ selected.content }}</blockquote>
      <a
        :href="'/api/knowledge/documents/' + selected.document_id + '/file'"
        class="knowledge-download"
        >下载已发布原文</a
      ></template
    >
  </dialog>
</template>
