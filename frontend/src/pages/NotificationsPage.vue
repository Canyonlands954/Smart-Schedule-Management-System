<script setup lang="ts">
import { computed, ref } from "vue";
import { useWorkspace } from "../stores/workspace";
import type { Notice } from "../domain/types";
import { label } from "../domain/time";
import PageHead from "../components/PageHead.vue";
import Icon from "../components/Icon.vue";
const store = useWorkspace(),
  filter = ref("all"),
  error = ref("");
const notices = computed(
  () =>
    store.data?.notifications.filter(
      (n) =>
        filter.value === "all" ||
        (filter.value === "unread" && !n.is_read) ||
        (filter.value === "pending" &&
          store.data?.events.some(
            (e) =>
              e.id === n.event_id &&
              e.target_id === store.user?.id &&
              e.status === "pending",
          )),
    ) ?? [],
);
async function read(n?: Notice, open = false) {
  try {
    await store.mutate(
      "/notifications/read",
      "POST",
      n ? { id: n.id } : {},
      "通知已标为已读",
    );
    if (n && open) {
      const e = store.data?.events.find((e) => e.id === n.event_id);
      if (e) store.open(e);
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作失败";
  }
}
</script>
<template>
  <PageHead
    title="通知中心"
    :description="'你有 ' + (store.data?.unread_count ?? 0) + ' 条未读通知'"
    ><button @click="read()">全部标为已读</button></PageHead
  >
  <div class="notification-toolbar">
    <div class="page-tabs">
      <button
        v-for="f in [
          ['all', '全部通知'],
          ['unread', '未读通知'],
          ['pending', '待我确认'],
        ]"
        :key="f[0]"
        :class="{ selected: filter === f[0] }"
        @click="filter = f[0]"
      >
        {{ f[1] }}
      </button>
    </div>
    <small>显示最近 {{ store.data?.notifications.length }} 条通知</small>
  </div>
  <section class="panel">
    <div
      v-for="n in notices"
      :key="n.id"
      class="notification-row"
      :class="{ unread: !n.is_read }"
    >
      <span class="notice-symbol"><Icon name="bell" /></span>
      <div class="notice-content">
        <div class="notice-heading">
          <h3>{{ n.title }}</h3>
          <span v-if="!n.is_read" class="unread-dot" /><small>{{
            label(n.created_at)
          }}</small>
        </div>
        <p>{{ n.body }}</p>
        <div class="actions">
          <button
            v-if="
              n.event_id && store.data?.events.some((e) => e.id === n.event_id)
            "
            class="small"
            @click="read(n, true)"
          >
            查看安排</button
          ><button v-if="!n.is_read" class="small" @click="read(n)">
            标为已读
          </button>
        </div>
      </div>
    </div>
    <div v-if="!notices.length" class="empty">这里暂时没有通知</div>
  </section>
  <div class="formerror">{{ error }}</div>
</template>
