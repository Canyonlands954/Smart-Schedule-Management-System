<script setup lang="ts">
import { computed, ref } from "vue";
import { useWorkspace } from "../stores/workspace";
import { today, shift, local, active, label, inDay } from "../domain/time";
import PageHead from "../components/PageHead.vue";
import EventRow from "../components/EventRow.vue";
const store = useWorkspace(),
  date = ref(today()),
  mode = ref("week"),
  member = ref("all"),
  state = ref("active"),
  query = ref("");
const filtered = computed(
  () =>
    store.data?.events.filter(
      (e) =>
        (member.value === "all" || e.target_id === Number(member.value)) &&
        (state.value === "all" ||
          (state.value === "active" && active(e)) ||
          e.status === state.value) &&
        (e.title + e.location + e.target_name).includes(query.value),
    ) ?? [],
);
const days = computed(() => {
  if (mode.value === "day") return [date.value];
  const current = new Date(date.value + "T12:00:00+08:00");
  if (mode.value === "month") {
    const first = date.value.slice(0, 8) + "01";
    const offset = (new Date(first + "T12:00:00+08:00").getUTCDay() + 6) % 7;
    return Array.from({ length: 42 }, (_, i) => shift(first, i - offset));
  }
  const offset = (current.getUTCDay() + 6) % 7;
  return Array.from({ length: 7 }, (_, i) => shift(date.value, i - offset));
});
function move(n: number) {
  if (mode.value === "month") {
    const [y, m, d] = date.value.split("-").map(Number);
    const days = new Date(Date.UTC(y, m + n, 0)).getUTCDate();
    date.value = local(
      new Date(Date.UTC(y, m - 1 + n, Math.min(d, days), 4)),
    ).slice(0, 10);
    return;
  }
  date.value = shift(
    date.value,
    n * (mode.value === "month" ? 30 : mode.value === "week" ? 7 : 1),
  );
}
function exportCalendar() {
  const esc = (s: string) =>
    s
      .replace(/\\/g, "\\\\")
      .replace(/\n/g, "\\n")
      .replace(/,/g, "\\,")
      .replace(/;/g, "\\;");
  const utc = (s: string) =>
    new Date(s).toISOString().replace(/[-:]/g, "").slice(0, 15) + "Z";
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Xushi//Schedule//ZH",
    ...filtered.value.flatMap((e) => [
      "BEGIN:VEVENT",
      "UID:xushi-" + e.id + "@schedule",
      "DTSTAMP:" + utc(new Date().toISOString()),
      "DTSTART:" + utc(e.start),
      "DTEND:" + utc(e.end),
      "SUMMARY:" + esc(e.title),
      "LOCATION:" + esc(e.location),
      "DESCRIPTION:" + esc(e.notes),
      "END:VEVENT",
    ]),
    "END:VCALENDAR",
  ];
  const url = URL.createObjectURL(
    new Blob([lines.join("\r\n") + "\r\n"], {
      type: "text/calendar;charset=utf-8",
    }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = "序时日程.ics";
  a.click();
  URL.revokeObjectURL(url);
}
</script>
<template>
  <PageHead title="我的日程" description="用清晰的日历，安排好每一件重要的事"
    ><button @click="exportCalendar">导出日历</button
    ><button
      class="primary"
      @click="
        store.create({
          start: date + 'T09:00:00+08:00',
          end: date + 'T10:00:00+08:00',
          ...(member !== 'all' ? { target_id: Number(member) } : {}),
        })
      "
    >
      ＋ 新建日程
    </button></PageHead
  >
  <div class="filterbar">
    <div class="filter-controls">
      <label class="filterlabel"
        >查看日程<select v-model="member">
          <option value="all">可见成员</option>
          <option
            v-for="u in store.data?.users.filter((u) => u.active)"
            :key="u.id"
            :value="u.id"
          >
            {{ u.name }}
          </option>
        </select></label
      ><select v-model="state" aria-label="日程状态">
        <option value="active">待进行安排</option>
        <option value="all">全部日程</option>
        <option value="pending">待确认</option>
        <option value="accepted">已接受</option>
      </select>
    </div>
    <input
      v-model="query"
      type="search"
      aria-label="查找日程"
      placeholder="查找标题、地点或成员"
    />
  </div>
  <section class="panel">
    <div class="panelhead">
      <h2>
        {{ mode === "day" ? date : days[0] + " — " + days[days.length - 1] }}
      </h2>
      <div class="calendar-tools">
        <button aria-label="上一页日期" @click="move(-1)">‹</button
        ><button @click="date = today()">今天</button
        ><button aria-label="下一页日期" @click="move(1)">›</button
        ><input v-model="date" type="date" aria-label="选择日期" />
        <div class="segmented">
          <button
            v-for="m in [
              ['day', '日'],
              ['week', '周'],
              ['month', '月'],
            ]"
            :key="m[0]"
            :class="{ selected: mode === m[0] }"
            @click="mode = m[0]"
          >
            {{ m[1] }}
          </button>
        </div>
      </div>
    </div>
    <div v-if="mode === 'day'" class="day-list">
      <EventRow
        v-for="e in filtered.filter(
          (e) => inDay(e, date) && local(e.end).slice(0, 10) >= date,
        )"
        :key="e.id"
        :event="e"
      />
      <div v-if="!filtered.some((e) => inDay(e, date))" class="empty">
        当天暂无安排
      </div>
    </div>
    <div v-else class="calendar-scroll">
      <div :class="mode === 'week' ? 'week-grid' : 'monthgrid'">
        <section
          v-for="(day, i) in days"
          :key="day"
          :class="[
            mode === 'week' ? 'week-day' : 'month-cell',
            { 'current-day': day === today() },
          ]"
        >
          <button
            class="week-date"
            @click="
              date = day;
              mode = 'day';
            "
          >
            <span
              >周{{ ["一", "二", "三", "四", "五", "六", "日"][i % 7] }}</span
            ><b>{{ Number(day.slice(8)) }}</b>
          </button>
          <div class="week-items">
            <button
              v-for="e in filtered.filter((e) => inDay(e, day))"
              :key="e.id"
              class="calendar-event"
              :class="{
                pending: e.status === 'pending',
                completed: e.phase === 'completed',
              }"
              @click="store.open(e)"
            >
              <small
                >{{ local(e.start).slice(11) }}–{{
                  local(e.end).slice(11)
                }}</small
              ><b>{{ e.title }}</b
              ><span>{{ e.target_name }}</span
              ><span class="event-place">{{ e.location }}</span></button
            ><span v-if="!filtered.some((e) => inDay(e, day))" class="free-time"
              >暂无安排</span
            ><button
              class="add-date"
              :aria-label="'为' + day + '新建日程'"
              @click="
                store.create({
                  start: day + 'T09:00:00+08:00',
                  end: day + 'T10:00:00+08:00',
                  ...(member !== 'all' ? { target_id: Number(member) } : {}),
                })
              "
            >
              ＋
            </button>
          </div>
        </section>
      </div>
    </div>
  </section>
  <div class="calendar-summary">
    <span>{{ filtered.length }} 项日程 · 北京时间</span
    ><span
      ><i class="legend-dot" />已接受 <i class="legend-dot pending" />待确认
      <i class="legend-dot completed" />已完成</span
    >
  </div>
</template>
