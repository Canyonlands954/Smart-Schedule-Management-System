<script setup lang="ts">
defineProps<{ name?: string }>();
const paths: Record<string, string> = {
  calendar: "M4 5h16v16H4z M8 2v6 M16 2v6 M4 10h16",
  grid: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  users:
    "M16 21v-3a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v3 M9 10a4 4 0 1 0 0-8a4 4 0 0 0 0 8 M20 21v-3a4 4 0 0 0-3-4 M16 2a4 4 0 0 1 0 8",
  check: "M4 3h16v18H4z M7 12l3 3 7-7",
  sparkles: "M12 2l3 7 7 3-7 3-3 7-3-7-7-3 7-3z",
  alert: "M12 3L2 21h20z M12 9v5 M12 17v1",
  bell: "M18 8a6 6 0 0 0-12 0v6l-2 3h16l-2-3z M10 21h4",
  settings:
    "M12 8a4 4 0 1 0 0 8a4 4 0 0 0 0-8 M4 4l4 1 4-3 4 3 4-1 2 8-2 8-4-1-4 3-4-3-4 1-2-8z",
  chart: "M4 3v18h18 M8 17v-5 M13 17V7 M18 17V4",
  search: "M10 3a7 7 0 1 0 0 14a7 7 0 0 0 0-14 M15 15l6 6",
  clock: "M12 2a10 10 0 1 0 0 20a10 10 0 0 0 0-20 M12 6v6l4 3",
  folder: "M3 6V3h6l3 3h9v15H3z",
  refresh: "M20 7v5h-5 M4 17v-5h5 M6 6a8 8 0 0 1 13 2 M18 18a8 8 0 0 1-13-2",
  pin: "M12 22s8-8 8-13a8 8 0 0 0-16 0c0 5 8 13 8 13 M12 6a3 3 0 1 0 0 6a3 3 0 0 0 0-6",
  logout: "M9 4H4v16h5 M14 8l4 4-4 4 M8 12h13",
};
</script>
<template>
  <svg
    class="icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name ?? 'calendar'] ?? paths.calendar" />
  </svg>
</template>
