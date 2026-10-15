<script setup lang="ts">
import { onMounted, watch } from "vue";
import { useRouter } from "vue-router";
import { useWorkspace } from "./stores/workspace";
import EventDialog from "./components/EventDialog.vue";
const store = useWorkspace(),
  router = useRouter();
onMounted(async () => {
  try {
    await store.boot();
    if (!store.user) await router.replace("/login");
    else if (router.currentRoute.value.path === "/login")
      await router.replace("/dashboard");
  } catch (e) {
    store.error = e instanceof Error ? e.message : "服务连接失败";
    store.ready = true;
  }
});
watch(
  () => store.user,
  (u) => {
    if (store.ready && !u) void router.replace("/login");
  },
);
</script>
<template>
  <div v-if="!store.ready" class="boot">正在连接工作空间…</div>
  <div v-else-if="!store.user && store.error" class="boot">
    <div>
      <p>{{ store.error }}</p>
      <button @click="store.boot">重新连接</button>
    </div>
  </div>
  <RouterView v-else /><EventDialog
    v-if="store.user && (store.selected || store.editor)"
  />
  <div v-if="store.message" id="toast" class="show" role="status">
    {{ store.message }}
  </div>
</template>
