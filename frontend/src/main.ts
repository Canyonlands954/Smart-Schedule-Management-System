import { createApp } from "vue";
import { createPinia } from "pinia";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import WorkspaceLayout from "./layouts/WorkspaceLayout.vue";
import AuthPage from "./pages/AuthPage.vue";
import "./styles/base.css";
import "./styles/components.css";
import "./styles/vue.css";
import "./styles/knowledge.css";
const pages = [
  ["dashboard", () => import("./pages/DashboardPage.vue")],
  ["calendar", () => import("./pages/CalendarPage.vue")],
  ["execution", () => import("./pages/ExecutionPage.vue")],
  ["ai", () => import("./pages/AiPage.vue")],
  ["knowledge", () => import("./pages/KnowledgePage.vue")],
  ["teams", () => import("./pages/TeamsPage.vue")],
  ["notifications", () => import("./pages/NotificationsPage.vue")],
  ["settings", () => import("./pages/SettingsPage.vue")],
  ["conflicts", () => import("./pages/ConflictsPage.vue")],
  ["metrics", () => import("./pages/MetricsPage.vue")],
] as const;
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", component: AuthPage },
    {
      path: "/",
      component: WorkspaceLayout,
      children: [
        { path: "", redirect: "/dashboard" },
        ...pages.map(([path, component]) => ({ path, component })),
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/dashboard" },
  ],
});
createApp(App).use(createPinia()).use(router).mount("#app");
