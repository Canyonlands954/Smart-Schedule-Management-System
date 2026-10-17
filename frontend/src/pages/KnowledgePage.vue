<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from "vue";
import { useRouter } from "vue-router";
import { api, upload } from "../services/api";
import { useWorkspace } from "../stores/workspace";
import { today } from "../domain/time";
import type {
  KnowledgeBase,
  KnowledgeDocument,
  KnowledgeVersion,
  KnowledgeReply,
  Citation,
} from "../domain/knowledge";
import PageHead from "../components/PageHead.vue";
import KnowledgeAnswer from "../components/KnowledgeAnswer.vue";
import SourceCitations from "../components/SourceCitations.vue";
const store = useWorkspace(),
  router = useRouter();
const bases = ref<KnowledgeBase[]>([]),
  docs = ref<KnowledgeDocument[]>([]),
  base = ref(""),
  tab = ref("documents"),
  error = ref(""),
  message = ref(""),
  busy = ref(false),
  loading = ref(false);
const baseEditor = ref(false),
  baseId = ref<number | null>(null),
  baseRevision = ref(0);
const baseForm = reactive({
  name: "",
  description: "",
  scope: "all",
  team_id: null as number | null,
  user_ids: [] as number[],
});
const uploadEditor = ref(false),
  uploadDoc = ref<KnowledgeDocument | null>(null),
  file = ref<File | null>(null);
const uploadForm = reactive({
  base_id: 0,
  title: "",
  effective_from: today(),
  effective_until: "",
  external_allowed: false,
});
const selected = ref<KnowledgeDocument | null>(null),
  versions = ref<KnowledgeVersion[]>([]),
  confirmAction = ref("");
const previewVersion = ref<KnowledgeVersion | null>(null),
  previewOffset = ref(0),
  previewTotal = ref(0),
  previewChunks = ref<{ ordinal: number; locator: string; content: string }[]>(
    [],
  );
async function preview(v: KnowledgeVersion, offset = 0) {
  error.value = "";
  try {
    const r = await api<{ items: typeof previewChunks.value; total: number }>(
      "/knowledge/versions/" + v.id + "/preview?offset=" + offset,
    );
    previewVersion.value = v;
    previewOffset.value = offset;
    previewTotal.value = r.total;
    previewChunks.value = r.items;
  } catch (e) {
    failure(e);
  }
}
const question = ref(""),
  reply = ref<KnowledgeReply | null>(null),
  hits = ref<Citation[]>([]),
  searchDone = ref(false),
  askBusy = ref(false),
  followup = ref(false);
const stats = ref<{
  queries: {
    total: number;
    retrieval_ms: number;
    total_ms: number;
    helpful: number;
    unhelpful: number;
    abstained: number;
    answer_tokens: number;
    metered: number;
  };
} | null>(null);
const currentBase = computed(() =>
  bases.value.find((x) => x.id === Number(base.value)),
);
let timer: ReturnType<typeof setInterval> | undefined;
function failure(e: unknown) {
  error.value = e instanceof Error ? e.message : "操作失败";
}
async function refresh() {
  if (loading.value) return;
  loading.value = true;
  try {
    bases.value = (
      await api<{ items: KnowledgeBase[] }>("/knowledge/bases")
    ).items;
    docs.value = (
      await api<{ items: KnowledgeDocument[] }>(
        "/knowledge/documents" + (base.value ? "?base_id=" + base.value : ""),
      )
    ).items;
    if (selected.value) {
      selected.value =
        docs.value.find((d) => d.id === selected.value?.id) ?? null;
      if (selected.value) await loadVersions(selected.value);
    }
  } catch (e) {
    failure(e);
  } finally {
    loading.value = false;
  }
}
onMounted(async () => {
  await refresh();
  timer = setInterval(() => {
    if (
      !busy.value &&
      docs.value.some(
        (d) => d.job_status === "queued" || d.job_status === "processing",
      )
    )
      void refresh();
  }, 4000);
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
});
watch(base, () => {
  selected.value = null;
  reply.value = null;
  hits.value = [];
  searchDone.value = false;
  void refresh();
});
function editBase(edit = false) {
  error.value = "";
  const b = edit ? currentBase.value : null;
  baseId.value = b?.id ?? null;
  baseRevision.value = b?.revision ?? 0;
  Object.assign(
    baseForm,
    b
      ? {
          name: b.name,
          description: b.description,
          scope: b.scope,
          team_id: b.team_id,
          user_ids: [...(b.user_ids ?? [])],
        }
      : {
          name: "",
          description: "",
          scope: "all",
          team_id: null,
          user_ids: [],
        },
  );
  baseEditor.value = true;
}
async function saveBase() {
  busy.value = true;
  error.value = "";
  try {
    const result = await api<{ id: number }>(
      "/knowledge/bases" + (baseId.value ? "/" + baseId.value : ""),
      baseId.value ? "PUT" : "POST",
      { ...baseForm, revision: baseRevision.value },
    );
    baseEditor.value = false;
    base.value = String(result.id);
    await refresh();
    message.value = "知识库及访问范围已保存。";
  } catch (e) {
    failure(e);
  } finally {
    busy.value = false;
  }
}
function openUpload(d?: KnowledgeDocument) {
  error.value = "";
  uploadDoc.value = d ?? null;
  file.value = null;
  Object.assign(uploadForm, {
    base_id: d?.base_id ?? Number(base.value || bases.value[0]?.id || 0),
    title: d?.title ?? "",
    effective_from: today(),
    effective_until: "",
    external_allowed: false,
  });
  uploadEditor.value = true;
}
function chooseFile(e: Event) {
  file.value = (e.target as HTMLInputElement).files?.[0] ?? null;
  if (file.value && !uploadForm.title)
    uploadForm.title = file.value.name.replace(/\.[^.]+$/, "");
}
async function saveUpload() {
  if (!file.value) {
    error.value = "请选择文件";
    return;
  }
  if (file.value.size > 10 * 1024 * 1024) {
    error.value = "文件不能超过10MB";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await upload(
      "/knowledge/documents" +
        (uploadDoc.value ? "/" + uploadDoc.value.id + "/versions" : ""),
      file.value,
      { ...uploadForm, revision: uploadDoc.value?.revision },
    );
    uploadEditor.value = false;
    message.value = "文件已上传，正在后台处理。处理完成后请检查并发布。";
    await refresh();
  } catch (e) {
    failure(e);
  } finally {
    busy.value = false;
  }
}
async function loadVersions(d: KnowledgeDocument) {
  versions.value = (
    await api<{ items: KnowledgeVersion[] }>(
      "/knowledge/documents/" + d.id + "/versions",
    )
  ).items;
}
async function select(d: KnowledgeDocument) {
  previewVersion.value = null;
  selected.value = d;
  confirmAction.value = "";
  error.value = "";
  try {
    await loadVersions(d);
  } catch (e) {
    failure(e);
  }
}
async function act(action: string, v?: KnowledgeVersion) {
  if (!selected.value) return;
  busy.value = true;
  error.value = "";
  try {
    await api(
      "/knowledge/documents/" + selected.value.id + "/" + action,
      "POST",
      { revision: selected.value.revision, version_id: v?.id },
    );
    message.value =
      action === "publish"
        ? "版本已发布，后续检索使用新资料。"
        : action === "delete"
          ? "资料已删除，引用已失效。"
          : "资料已停用。";
    confirmAction.value = "";
    await refresh();
  } catch (e) {
    failure(e);
  } finally {
    busy.value = false;
  }
}
async function retry(v: KnowledgeVersion) {
  busy.value = true;
  error.value = "";
  try {
    await api("/knowledge/versions/" + v.id + "/retry", "POST");
    message.value = "已加入处理队列。";
    await refresh();
  } catch (e) {
    failure(e);
  } finally {
    busy.value = false;
  }
}
async function ask(generate: boolean) {
  if (!question.value.trim() || askBusy.value) return;
  askBusy.value = true;
  error.value = "";
  hits.value = [];
  searchDone.value = false;
  try {
    if (generate) {
      reply.value = await api<KnowledgeReply>("/knowledge/ask", "POST", {
        question: question.value,
        base_id: Number(base.value) || undefined,
        previous_query_id: followup.value ? reply.value?.query_id : undefined,
      });
    } else {
      reply.value = null;
      const r = await api<{ items: Citation[]; warning: string }>(
        "/knowledge/search",
        "POST",
        {
          question: question.value,
          base_id: Number(base.value) || undefined,
          semantic: store.admin,
        },
      );
      hits.value = r.items;
      searchDone.value = true;
      message.value = r.warning;
    }
  } catch (e) {
    failure(e);
  } finally {
    askBusy.value = false;
  }
}
async function useInSchedule() {
  if (!reply.value) return;
  store.aiText = question.value;
  store.aiKnowledgeQuery = reply.value.query_id;
  await router.push("/ai");
}
async function loadStats() {
  try {
    stats.value = await api("/knowledge/stats");
  } catch (e) {
    failure(e);
  }
}
function status(v: string | null) {
  return (
    (
      {
        queued: "等待处理",
        processing: "处理中",
        ready: "已就绪",
        failed: "处理失败",
        done: "处理完成",
      } as Record<string, string>
    )[v ?? ""] ?? "待上传"
  );
}
</script>
<template>
  <PageHead
    title="企业知识库"
    description="让制度与项目资料成为每项安排的可靠依据"
  />
  <div class="knowledge-toolbar">
    <div class="knowledge-tabs" role="group" aria-label="知识库功能">
      <button
        :class="{ active: tab === 'documents' }"
        @click="tab = 'documents'"
      >
        资料库</button
      ><button :class="{ active: tab === 'ask' }" @click="tab = 'ask'">
        {{ store.admin ? "知识问答" : "资料检索" }}
      </button>
    </div>
    <div v-if="store.admin" class="actions">
      <button @click="editBase()">＋ 新建知识库</button
      ><button class="primary" :disabled="!bases.length" @click="openUpload()">
        上传资料
      </button>
    </div>
  </div>
  <div v-if="error" class="warning-box" role="alert">{{ error }}</div>
  <div v-if="message" class="success-box" role="status">{{ message }}</div>
  <div class="knowledge-filter">
    <label
      >查找范围<select v-model="base">
        <option value="">全部有权访问的知识库</option>
        <option v-for="b in bases" :key="b.id" :value="String(b.id)">
          {{ b.name }} · {{ b.document_count }} 份
        </option>
      </select></label
    ><button
      v-if="store.admin && currentBase"
      :disabled="busy"
      @click="editBase(true)"
    >
      编辑知识库与权限</button
    ><button :disabled="loading" @click="refresh">
      {{ loading ? "刷新中…" : "刷新资料" }}
    </button>
  </div>
  <section
    v-if="store.admin && baseEditor"
    class="panel section-gap knowledge-editor"
  >
    <div class="panelhead">
      <h2>{{ baseId ? "编辑知识库" : "新建知识库" }}</h2>
      <button :disabled="busy" @click="baseEditor = false">取消</button>
    </div>
    <form class="form" @submit.prevent="saveBase">
      <div class="two">
        <label
          >名称<input v-model="baseForm.name" required maxlength="100" /></label
        ><label
          >访问范围<select v-model="baseForm.scope">
            <option value="all">全员可读</option>
            <option value="team">指定团队可读</option>
            <option value="private">仅管理员与单独授权用户</option>
          </select></label
        >
      </div>
      <label v-if="baseForm.scope === 'team'"
        >所属团队<select v-model="baseForm.team_id" required>
          <option :value="null" disabled>请选择团队</option>
          <option v-for="t in store.data?.teams" :key="t.id" :value="t.id">
            {{ t.name }}
          </option>
        </select></label
      ><label
        >说明<textarea v-model="baseForm.description" maxlength="1000" /></label
      ><label
        >另外授权的用户（按住 Ctrl 多选）<select
          v-model="baseForm.user_ids"
          multiple
          size="4"
        >
          <option
            v-for="u in store.data?.users.filter(
              (u) => u.active && u.role !== 'admin',
            )"
            :key="u.id"
            :value="u.id"
          >
            {{ u.name }} · {{ u.username }}
          </option>
        </select></label
      >
      <p class="muted">
        平台管理员可管理全部资料；权限修改会立即影响检索、引用和原文下载。
      </p>
      <div class="actions">
        <button class="primary" :disabled="busy">保存知识库</button>
      </div>
    </form>
  </section>
  <section
    v-if="store.admin && uploadEditor"
    class="panel section-gap knowledge-editor"
  >
    <div class="panelhead">
      <h2>
        {{ uploadDoc ? "上传新版本 · " + uploadDoc.title : "上传企业资料" }}
      </h2>
      <button :disabled="busy" @click="uploadEditor = false">取消</button>
    </div>
    <form class="form" @submit.prevent="saveUpload">
      <div class="two">
        <label
          >所属知识库<select
            v-model="uploadForm.base_id"
            required
            :disabled="!!uploadDoc"
          >
            <option v-for="b in bases" :key="b.id" :value="b.id">
              {{ b.name }}
            </option>
          </select></label
        ><label
          >资料标题<input
            v-model="uploadForm.title"
            required
            maxlength="150"
            :disabled="!!uploadDoc"
        /></label>
      </div>
      <label
        >资料文件<input
          :key="uploadDoc?.id ?? 'new'"
          type="file"
          accept=".pdf,.docx,.md,.txt"
          required
          @change="chooseFile"
        /><small
          >支持文本型 PDF、Word（DOCX）、Markdown、UTF-8
          文本，最大10MB；扫描件请先识别文字。</small
        ></label
      >
      <div class="two">
        <label
          >生效日期<input
            v-model="uploadForm.effective_from"
            type="date"
            required /></label
        ><label
          >失效日期（可选）<input
            v-model="uploadForm.effective_until"
            type="date"
            :min="uploadForm.effective_from"
        /></label>
      </div>
      <label class="knowledge-check"
        ><input
          v-model="uploadForm.external_allowed"
          type="checkbox"
        />允许将本版本的正文片段发送至管理员配置的向量及问答模型服务</label
      >
      <p class="muted">
        未勾选时只在本站处理与检索原文。上传新版本后，已发布版本继续生效，直到你发布新版本。
      </p>
      <button class="primary" :disabled="busy">
        {{ busy ? "正在上传…" : "上传并处理" }}
      </button>
    </form>
  </section>
  <template v-if="tab === 'documents'">
    <div v-if="!docs.length" class="panel knowledge-empty">
      <h2>{{ loading ? "正在读取资料…" : "把企业经验整理成可引用的知识" }}</h2>
      <p>
        {{
          store.admin
            ? "先新建知识库，再上传会议制度、项目说明或协作流程。"
            : "管理员发布资料后，你可以在这里查阅权限范围内的制度和项目说明。"
        }}
      </p>
    </div>
    <div v-else class="knowledge-doc-grid">
      <article v-for="d in docs" :key="d.id" class="panel knowledge-doc">
        <div class="knowledge-doc-top">
          <span class="source-marker">文</span
          ><span class="tag">{{
            store.admin
              ? status(
                  d.job_status === "processing" || d.job_status === "queued"
                    ? d.job_status
                    : d.status,
                )
              : "已发布"
          }}</span>
        </div>
        <h3>{{ d.title }}</h3>
        <p class="muted">
          {{ d.base_name }} · v{{ d.version ?? 1 }} ·
          {{ d.chunk_count ?? 0 }} 个片段
        </p>
        <p class="knowledge-doc-state">
          {{
            d.published_version_id ? "已有发布版本" : "尚未发布，仅管理员可见"
          }}
        </p>
        <p v-if="d.error" class="formerror">{{ d.error }}</p>
        <div class="actions">
          <button v-if="store.admin" class="small" @click="select(d)">
            版本与发布</button
          ><a
            v-if="d.published_version_id"
            :href="'/api/knowledge/documents/' + d.id + '/file'"
            >下载原文</a
          >
        </div>
      </article>
    </div>
    <section v-if="selected && store.admin" class="panel section-gap">
      <div class="panelhead">
        <h2>{{ selected.title }} · 版本管理</h2>
        <button @click="selected = null">收起</button>
      </div>
      <div class="panelbody">
        <div class="actions">
          <button :disabled="busy" @click="openUpload(selected)">
            上传新版本</button
          ><button
            :disabled="busy || !selected.published_version_id"
            @click="confirmAction = 'unpublish'"
          >
            停用资料</button
          ><button
            class="danger"
            :disabled="busy"
            @click="confirmAction = 'delete'"
          >
            删除资料
          </button>
        </div>
        <div v-if="confirmAction" class="warning-box">
          {{
            confirmAction === "delete"
              ? "删除后该资料退出检索，已有引用将失效。"
              : "停用后资料退出检索，已有引用将失效。"
          }}
          <div class="actions">
            <button :disabled="busy" @click="confirmAction = ''">取消</button
            ><button
              class="danger"
              :disabled="busy"
              @click="act(confirmAction)"
            >
              确认{{ confirmAction === "delete" ? "删除" : "停用" }}
            </button>
          </div>
        </div>
        <article v-for="v in versions" :key="v.id" class="knowledge-version">
          <div>
            <strong>v{{ v.version }} · {{ v.filename }}</strong>
            <p class="muted">
              {{ v.effective_from }} 至 {{ v.effective_until ?? "长期有效" }} ·
              {{ v.chunk_count }} 个片段 ·
              {{ v.space_id ? "已生成向量" : "关键词检索" }} ·
              {{ v.external_allowed ? "允许模型处理" : "仅站内检索" }}
            </p>
            <span class="tag">{{
              selected.published_version_id === v.id
                ? "当前发布版本"
                : status(
                    v.job_status === "processing" || v.job_status === "queued"
                      ? v.job_status
                      : v.status,
                  )
            }}</span>
            <p v-if="v.error" class="formerror">{{ v.error }}</p>
          </div>
          <div class="actions">
            <button
              v-if="
                v.status === 'ready' && selected.published_version_id !== v.id
              "
              class="small primary"
              :disabled="busy"
              @click="act('publish', v)"
            >
              发布此版本</button
            ><button
              class="small"
              :disabled="
                busy ||
                v.job_status === 'queued' ||
                v.job_status === 'processing'
              "
              @click="retry(v)"
            >
              {{ v.status === "failed" ? "重试处理" : "重新处理" }}
            </button>
            <button
              v-if="v.status === 'ready'"
              class="small"
              :disabled="busy"
              @click="preview(v)"
            >
              检查正文
            </button>
          </div>
        </article>
        <section v-if="previewVersion" class="knowledge-version-preview">
          <div class="actions">
            <h3>正文检查 · v{{ previewVersion.version }}</h3>
            <button class="small" @click="previewVersion = null">
              收起正文</button
            ><a :href="'/api/knowledge/versions/' + previewVersion.id + '/file'"
              >下载此版本原文</a
            >
          </div>
          <p class="muted">
            第 {{ previewOffset + 1 }}—{{
              Math.min(previewOffset + 20, previewTotal)
            }}
            个片段，共 {{ previewTotal }} 个。
          </p>
          <article v-for="c in previewChunks" :key="c.ordinal">
            <small>{{ c.locator }}</small>
            <blockquote class="knowledge-excerpt">{{ c.content }}</blockquote>
          </article>
          <div class="actions">
            <button
              :disabled="previewOffset === 0"
              @click="preview(previewVersion, previewOffset - 20)"
            >
              上一页</button
            ><button
              :disabled="previewOffset + 20 >= previewTotal"
              @click="preview(previewVersion, previewOffset + 20)"
            >
              下一页
            </button>
          </div>
        </section>
      </div>
    </section>
  </template>
  <section v-else class="panel knowledge-query-panel">
    <div class="panelhead">
      <h2>{{ store.admin ? "基于企业资料提问" : "检索企业资料" }}</h2>
      <span class="tag">权限内的有效资料</span>
    </div>
    <form class="form" @submit.prevent="ask(store.admin)">
      <label for="knowledge-question"
        >{{ store.admin ? "你想了解什么？" : "输入关键词"
        }}<textarea
          id="knowledge-question"
          v-model="question"
          required
          maxlength="2000"
          :disabled="askBusy"
          placeholder="例如：项目例会需要提前准备哪些资料？"
        /></label
      ><label v-if="store.admin && reply" class="knowledge-check"
        ><input
          v-model="followup"
          type="checkbox"
          :disabled="askBusy"
        />结合上一轮问题继续追问</label
      >
      <div class="actions">
        <button v-if="store.admin" class="primary" :disabled="askBusy">
          {{ askBusy ? "正在查找依据…" : "提问并查看依据" }}</button
        ><button type="button" :disabled="askBusy" @click="ask(false)">
          {{ store.admin ? "只检索资料" : "检索资料" }}</button
        ><button
          v-if="store.admin && reply?.mode === 'answer'"
          type="button"
          :disabled="askBusy"
          @click="useInSchedule"
        >
          带入日程助手
        </button>
      </div>
      <p class="muted">
        {{
          store.admin
            ? "回答会展示原文引用；资料不足时会明确说明。实时忙闲由日程助手查询。"
            : "搜索和查阅不调用外部模型。"
        }}
      </p>
    </form>
    <div class="panelbody">
      <KnowledgeAnswer v-if="reply" :key="reply.query_id" :reply="reply" />
      <p v-if="searchDone && !hits.length" class="muted">
        未找到相关的有效资料，请调整关键词或查找范围。
      </p>
      <article v-for="h in hits" :key="h.id" class="knowledge-hit">
        <blockquote class="knowledge-excerpt">{{ h.excerpt }}</blockquote>
        <SourceCitations :items="[h]" />
      </article>
    </div>
  </section>
  <section v-if="store.admin" class="panel section-gap">
    <div class="panelhead">
      <h2>知识服务使用情况</h2>
      <button class="small" @click="loadStats">读取统计</button>
    </div>
    <div v-if="stats" class="knowledge-metrics">
      <div>
        <strong>{{ stats.queries.total }}</strong
        ><span>近30天问答次数</span>
      </div>
      <div>
        <strong>{{ stats.queries.retrieval_ms }} ms</strong
        ><span>平均检索耗时</span>
      </div>
      <div>
        <strong>{{ stats.queries.total_ms }} ms</strong
        ><span>平均完整响应耗时</span>
      </div>
      <div>
        <strong
          >{{ stats.queries.helpful }} / {{ stats.queries.unhelpful }}</strong
        ><span>有帮助 / 需改进反馈</span>
      </div>
      <div>
        <strong>{{ stats.queries.abstained }}</strong
        ><span>依据不足次数</span>
      </div>
      <div>
        <strong>{{
          stats.queries.metered ? stats.queries.answer_tokens : "未返回"
        }}</strong
        ><span>问答 Token 用量（服务商返回）</span>
      </div>
    </div>
    <p v-else class="panelbody muted">
      按实际请求记录检索耗时、响应耗时和使用反馈。
    </p>
  </section>
</template>
