import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { api, ApiError } from "../services/api";
import type { Workspace, User, ScheduleEvent, Draft } from "../domain/types";
export const useWorkspace = defineStore("workspace", () => {
  const user = ref<User | null>(null),
    data = ref<Workspace | null>(null),
    initialized = ref(true),
    ready = ref(false),
    error = ref(""),
    message = ref(""),
    selected = ref<ScheduleEvent | null>(null),
    editor = ref<Partial<ScheduleEvent> | null>(null);
  const aiText = ref(""),
    aiKnowledgeQuery = ref(""),
    aiConversation = ref(""),
    aiRevision = ref(0),
    aiDraft = ref<Draft | null>(null),
    aiChat = ref<{ role: string; content: string }[]>([]);
  const admin = computed(() => user.value?.role === "admin");
  const manager = computed(() => admin.value || user.value?.is_leader === true);
  let timer: ReturnType<typeof setInterval> | undefined;
  let loading = false;
  async function boot() {
    const result = await api<{ initialized: boolean; user: User | null }>(
      "/auth/status",
    );
    initialized.value = result.initialized;
    user.value = result.user;
    if (user.value) await refresh();
    ready.value = true;
    if (!timer)
      timer = setInterval(() => {
        if (user.value && !selected.value && !editor.value) void refresh();
      }, 15000);
  }
  async function refresh() {
    if (loading) return;
    loading = true;
    try {
      data.value = await api<Workspace>("/state");
      user.value = data.value.user;
      error.value = "";
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        user.value = null;
        data.value = null;
        selected.value = null;
        editor.value = null;
        aiText.value = "";
        aiKnowledgeQuery.value = "";
        aiDraft.value = null;
        aiChat.value = [];
        aiConversation.value = "";
        aiRevision.value = 0;
        sessionStorage.removeItem("xushi-ai-conversation");
      } else error.value = e instanceof Error ? e.message : "同步失败";
    } finally {
      loading = false;
    }
  }
  async function login(username: string, password: string) {
    await api("/auth/login", "POST", { username, password });
    await refresh();
    initialized.value = true;
  }
  async function logout() {
    await api("/auth/logout", "POST");
    user.value = null;
    data.value = null;
    selected.value = null;
    editor.value = null;
    aiText.value = "";
    aiKnowledgeQuery.value = "";
    aiDraft.value = null;
    aiChat.value = [];
    aiConversation.value = "";
    aiRevision.value = 0;
    sessionStorage.removeItem("xushi-ai-conversation");
  }
  async function mutate(
    path: string,
    method: string,
    body: unknown,
    text = "操作已保存",
  ) {
    await api(path, method, body);
    await refresh();
    toast(text);
  }
  function toast(text: string) {
    message.value = text;
    setTimeout(() => {
      if (message.value === text) message.value = "";
    }, 4200);
  }
  function open(e: ScheduleEvent) {
    selected.value = e;
    editor.value = null;
  }
  function create(defaults: Partial<ScheduleEvent> = {}) {
    selected.value = null;
    editor.value = defaults;
  }
  return {
    aiText,
    aiKnowledgeQuery,
    aiConversation,
    aiRevision,
    aiDraft,
    aiChat,
    user,
    data,
    initialized,
    ready,
    error,
    message,
    selected,
    editor,
    admin,
    manager,
    boot,
    refresh,
    login,
    logout,
    mutate,
    toast,
    open,
    create,
  };
});
