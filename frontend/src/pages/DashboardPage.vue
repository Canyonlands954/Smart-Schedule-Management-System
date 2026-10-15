<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import { useWorkspace } from "../stores/workspace";
import { today, active } from "../domain/time";
import Icon from "../components/Icon.vue";
import EventRow from "../components/EventRow.vue";
const store = useWorkspace(),
  router = useRouter();
const events = computed(() => store.data?.events ?? []),
  pending = computed(() =>
    events.value.filter(
      (e) => e.target_id === store.user?.id && e.status === "pending",
    ),
  ),
  upcoming = computed(() =>
    events.value
      .filter(active)
      .filter((e) => new Date(e.end) > new Date())
      .slice(0, 6),
  ),
  changes = computed(() =>
    events.value.filter((e) => e.reschedule_request?.status === "pending"),
  );
</script>
<template>
  <section class="dashboard-hero">
    <div class="hero-copy">
      <div class="hero-date">{{ today() }}</div>
      <h1 class="hero-greeting">
        你好，{{ store.user?.name }}<span>让今天的协作，更有条理。</span>
      </h1>
      <p>安排、协调与跟进，都从这里开始。</p>
      <div class="quick-actions">
        <button class="quick-action primary" @click="store.create()">
          ＋ 新建日程</button
        ><button
          class="quick-action secondary"
          @click="router.push(store.manager ? '/ai' : '/calendar')"
        >
          <Icon :name="store.manager ? 'sparkles' : 'calendar'" />{{
            store.manager ? "AI 安排" : "查看日历"
          }}
        </button>
      </div>
    </div>
    <div class="hero-summary">
      <span class="hero-summary-item"
        ><Icon name="calendar" /><b>{{
          events.filter((e) => e.start.slice(0, 10) === today() && active(e))
            .length
        }}</b
        ><small>今日安排</small></span
      ><span class="hero-summary-item"
        ><Icon name="clock" /><b>{{ pending.length + changes.length }}</b
        ><small>需要你的处理</small></span
      >
    </div>
  </section>
  <div class="stats">
    <button
      v-for="s in [
        [
          '今日安排',
          events.filter((e) => e.start.slice(0, 10) === today() && active(e))
            .length,
          'calendar',
        ],
        ['等待你的确认', pending.length, 'notifications'],
        ['未读通知', store.data?.unread_count ?? 0, 'notifications'],
        ['待处理改期', changes.length, 'execution'],
      ]"
      :key="String(s[0])"
      class="stat"
      @click="router.push('/' + s[2])"
    >
      <div class="stat-label">
        {{ s[0] }}<span class="stat-icon"><Icon name="calendar" /></span>
      </div>
      <strong>{{ s[1] }}</strong
      ><small>查看相关安排 →</small>
    </button>
  </div>
  <div class="grid">
    <div class="today-agenda">
      <section v-if="pending.length" class="panel">
        <div class="panelhead"><h2>等待你的确认</h2></div>
        <EventRow v-for="e in pending" :key="e.id" :event="e" />
      </section>
      <section v-if="changes.length" class="panel">
        <div class="panelhead"><h2>需要处理的改期</h2></div>
        <EventRow v-for="e in changes" :key="e.id" :event="e" />
      </section>
      <section class="panel">
        <div class="panelhead">
          <h2>接下来的安排</h2>
          <button class="link" @click="router.push('/calendar')">
            查看日历
          </button>
        </div>
        <EventRow v-for="e in upcoming" :key="e.id" :event="e" />
        <div v-if="!upcoming.length" class="empty">暂无待进行的安排</div>
      </section>
    </div>
    <div class="rightstack">
      <section class="aicard">
        <div class="aiicon">
          <Icon :name="store.manager ? 'sparkles' : 'check'" />
        </div>
        <h2>
          {{ store.manager ? "让安排，从一句话开始" : "你的安排，等你回应" }}
        </h2>
        <p>
          {{
            store.manager
              ? "告诉助手谁、何时、在哪里，检查后发送给指定成员。"
              : "确认安排、准备资料、反馈结果，协作进度随时可见。"
          }}
        </p>
        <button
          class="primary"
          @click="router.push(store.manager ? '/ai' : '/execution')"
        >
          {{ store.manager ? "开始安排" : "查看执行看板" }} →
        </button>
      </section>
      <section class="panel">
        <div class="panelhead">
          <h2>最近动态</h2>
          <button class="link" @click="router.push('/notifications')">
            全部
          </button>
        </div>
        <div
          v-for="n in store.data?.notifications.slice(0, 3)"
          :key="n.id"
          class="notice"
        >
          <b>{{ n.title }}</b>
          <p>{{ n.body }}</p>
        </div>
        <div v-if="!store.data?.notifications.length" class="empty">
          暂无新的动态
        </div>
      </section>
    </div>
  </div>
</template>
