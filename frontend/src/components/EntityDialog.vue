<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted } from "vue";
import { useWorkspace } from "../stores/workspace";
export interface EntityField {
  key: string;
  label: string;
  type?: string;
  required?: boolean;
  options?: { value: string | number | null; label: string }[];
}
const props = defineProps<{
    title: string;
    path: string;
    method: string;
    fields: EntityField[];
    initial?: Record<string, unknown>;
  }>(),
  emit = defineEmits<{ close: []; saved: [] }>(),
  store = useWorkspace(),
  values = reactive<Record<string, string | number | null>>({}),
  error = ref(""),
  busy = ref(false),
  discard = ref(false);
for (const f of props.fields)
  values[f.key] = (props.initial?.[f.key] ?? (f.type === "number" ? 0 : "")) as
    string | number | null;
const baseline = JSON.stringify(values);
function close() {
  if (JSON.stringify(values) !== baseline) {
    discard.value = true;
    return;
  }
  emit("close");
}
function key(e: KeyboardEvent) {
  if (e.key === "Escape") close();
}
onMounted(() => document.addEventListener("keydown", key));
onUnmounted(() => document.removeEventListener("keydown", key));
async function save() {
  busy.value = true;
  error.value = "";
  try {
    await store.mutate(props.path, props.method, values);
    emit("saved");
    emit("close");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作失败";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div
    class="vue-dialog entity-dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby="entity-title"
  >
    <section class="panel">
      <div class="modalhead">
        <h3 id="entity-title">{{ title }}</h3>
        <button aria-label="关闭对话框" @click="close">×</button>
      </div>
      <div v-if="discard" class="warning-box">
        有尚未保存的修改
        <div class="actions">
          <button @click="discard = false">继续编辑</button
          ><button @click="emit('close')">放弃修改</button>
        </div>
      </div>
      <form class="form" @submit.prevent="save">
        <p v-if="method === 'DELETE'" class="warning-box">
          确认删除？仍有关联数据时，系统会提示先完成调整。
        </p>
        <label v-for="f in fields" :key="f.key"
          >{{ f.label
          }}<select
            v-if="f.options"
            v-model="values[f.key]"
            :aria-label="f.label"
          >
            <option v-for="(o, i) in f.options" :key="i" :value="o.value">
              {{ o.label }}
            </option></select
          ><textarea
            v-else-if="f.type === 'textarea'"
            v-model="values[f.key]"
            :required="f.required" /><input
            v-else
            v-model="values[f.key]"
            :name="f.key"
            :type="f.type ?? 'text'"
            :required="f.required"
            :autocomplete="f.type === 'password' ? 'new-password' : undefined"
            :minlength="f.type === 'password' ? 10 : undefined"
        /></label>
        <div class="formerror">{{ error }}</div>
        <div class="actions form-actions">
          <button type="button" @click="close">取消</button
          ><button class="primary" :disabled="busy" :aria-busy="busy">
            {{ method === "DELETE" ? "确认删除" : "保存" }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>
