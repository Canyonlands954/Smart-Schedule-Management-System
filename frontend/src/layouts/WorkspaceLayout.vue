<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useWorkspace } from "../stores/workspace";
import Icon from "../components/Icon.vue";
const store = useWorkspace(),
  route = useRoute(),
  router = useRouter(),
  query = ref(""),
  search = ref<HTMLInputElement | null>(null);
const navs = [
  ["dashboard", "grid", "工作台"],
  ["calendar", "calendar", "我的日程"],
  ["execution", "check", "执行看板"],
  ["ai", "sparkles", "AI 日程助手"],
  ["conflicts", "alert", "冲突管理"],
  ["teams", "users", "团队管理"],
  ["notifications", "bell", "通知中心"],
  ["settings", "settings", "账户与管理"],
  ["metrics", "chart", "协作分析"],
];
const visible = computed(() =>
  navs.filter(
    (n) =>
      (n[0] !== "ai" || store.manager) && (n[0] !== "metrics" || store.admin),
  ),
);
const title = computed(
  () => navs.find((n) => "/" + n[0] === route.path)?.[2] ?? "工作台",
);
const results = computed(() =>
  query.value
    ? store.data?.events
        .filter((e) =>
          (e.title + e.target_name + e.location).includes(query.value),
        )
        .slice(0, 6)
    : [],
);
function keyboard(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    search.value?.focus();
  }
}
onMounted(() => window.addEventListener("keydown", keyboard));
onUnmounted(() => window.removeEventListener("keydown", keyboard));
async function logout() {
  await store.logout();
  await router.replace("/login");
}
</script>
<template>
  <aside class="sidebar">
    <div class="brand">
      <span class="brandmark">序</span>
      <div>序时<span class="brand-en">SMART SCHEDULE</span></div>
    </div>
    <div class="workspace">
      <Icon name="folder" />
      <div>企业工作空间<span>北京时间 · 有序协作</span></div>
    </div>
    <div class="navlabel">工作空间</div>
    <nav aria-label="主要导航">
      <button
        v-for="n in visible"
        :key="n[0]"
        :class="{ active: route.path === '/' + n[0] }"
        :aria-current="route.path === '/' + n[0] ? 'page' : undefined"
        :data-id="n[0]"
        @click="router.push('/' + n[0])"
      >
        <span class="navicon"><Icon :name="n[1]" /></span>{{ n[2]
        }}<span
          v-if="n[0] === 'notifications' && store.data?.unread_count"
          class="count"
          >{{ store.data.unread_count }}</span
        >
      </button>
    </nav>
    <div class="sidebottom">
      <div class="demo">
        <b><span class="sync-dot" />工作空间已同步</b>
        <p>自动同步已开启</p>
        <button class="small" @click="store.refresh">
          <Icon name="refresh" />立即同步
        </button>
      </div>
      <button class="profile" @click="router.push('/settings')">
        <span class="avatar">{{ store.user?.name[0] }}</span>
        <div>
          {{ store.user?.name
          }}<small>{{
            store.admin
              ? "平台管理员"
              : store.manager
                ? "团队负责人"
                : "团队成员"
          }}</small>
        </div>
        ›
      </button>
    </div>
  </aside>
  <div class="shell">
    <header>
      <div class="breadcrumb">
        <span class="muted">工作空间</span><span class="crumb">/</span
        ><span>{{ title }}</span>
      </div>
      <div class="header-search">
        <label class="search-field"
          ><Icon name="search" /><input
            ref="search"
            v-model="query"
            type="search"
            placeholder="搜索日程、成员…"
            aria-label="搜索日程和成员"
          /><kbd>Ctrl K</kbd></label
        >
        <div v-if="query" class="search-results">
          <button
            v-for="e in results"
            :key="e.id"
            class="search-result"
            @click="
              store.open(e);
              query = '';
            "
          >
            {{ e.title }} · {{ e.target_name }}</button
          ><button
            v-for="u in store.data?.users
              .filter((u) => u.name.includes(query))
              .slice(0, 4)"
            :key="u.id"
            class="search-result"
            @click="
              router.push('/teams');
              query = '';
            "
          >
            {{ u.name }} · 成员目录
          </button>
          <p v-if="!results?.length" class="muted">没有匹配的日程</p>
        </div>
      </div>
      <div class="header-actions">
        <button class="header-notice" @click="router.push('/notifications')">
          <Icon name="bell" /><span>通知</span
          ><span class="header-count">{{
            store.data?.unread_count ?? 0
          }}</span></button
        ><button class="header-logout" aria-label="退出登录" @click="logout">
          <Icon name="logout" /><span>退出登录</span>
        </button>
      </div>
    </header>
    <main id="main">
      <div v-if="store.error" class="warning-box" role="alert">
        {{ store.error }}
      </div>
      <RouterView />
    </main>
    <footer>
      序时，让每一项安排都有着落。<span>智能日程 · 团队协作</span>
    </footer>
  </div>
</template>
