<script setup lang="ts">
import type { ScheduleEvent } from "../domain/types";
import { local, status } from "../domain/time";
import { useWorkspace } from "../stores/workspace";
import Icon from "./Icon.vue";
defineProps<{ event: ScheduleEvent }>();
const store = useWorkspace();
</script>
<template>
  <div class="event-card">
    <div class="event-clock">
      {{ local(event.start).slice(11)
      }}<small>{{ local(event.start).slice(0, 10) }}</small>
    </div>
    <div class="event-summary">
      <button class="event-title link" @click="store.open(event)">
        {{ event.title }}
      </button>
      <p>
        <Icon name="pin" />{{ event.location || "未指定地点" }} ·
        {{ event.target_name }}
      </p>
    </div>
    <span
      class="tag"
      :class="
        event.status === 'pending'
          ? 'warn'
          : event.status === 'accepted'
            ? ''
            : 'gray'
      "
      >{{ status(event) }}</span
    ><button
      class="icon-button"
      aria-label="查看日程"
      @click="store.open(event)"
    >
      ›
    </button>
  </div>
</template>
