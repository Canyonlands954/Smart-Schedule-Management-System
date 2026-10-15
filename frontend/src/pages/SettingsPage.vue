<script setup lang="ts">
import { actionName } from "../domain/actions";
import { ref } from "vue";
import { useWorkspace } from "../stores/workspace";
import { api } from "../services/api";
import type { Room, AuditEntry } from "../domain/types";
import PageHead from "../components/PageHead.vue";
import SchedulingPolicyForm from "../components/SchedulingPolicyForm.vue";
import ModelConfigurationForm from "../components/ModelConfigurationForm.vue";
import EntityDialog, { type EntityField } from "../components/EntityDialog.vue";
const store = useWorkspace(),
  code = ref(""),
  expiry = ref(""),
  logs = ref<AuditEntry[]>([]),
  error = ref(""),
  dialog = ref<{
    title: string;
    path: string;
    method: string;
    fields: EntityField[];
    initial?: Record<string, unknown>;
  } | null>(null);
function room(r?: Room) {
  dialog.value = {
    title: r ? "编辑会议室" : "添加会议室",
    path: "/rooms" + (r ? "/" + r.id : ""),
    method: r ? "PUT" : "POST",
    fields: [
      { key: "name", label: "会议室名称", required: true },
      { key: "capacity", label: "容量", type: "number", required: true },
    ],
    initial: { ...r, capacity: r?.capacity ?? 10 },
  };
}
async function invite() {
  try {
    const result = await api<{ code: string; expires_at: string }>(
      "/admin-invitations",
      "POST",
    );
    code.value = result.code;
    expiry.value = result.expires_at;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "生成失败";
  }
}
async function audit() {
  try {
    logs.value = (await api<{ items: AuditEntry[] }>("/audit")).items;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取失败";
  }
}
</script>
<template>
  <PageHead title="账户与管理" description="个人账户、会议室和操作记录" />
  <div class="two">
    <section class="panel">
      <div class="panelhead"><h2>个人账户</h2></div>
      <div class="panelbody">
        <p>{{ store.user?.name }} · {{ store.user?.username }}</p>
        <p class="muted">
          {{
            store.admin
              ? "平台管理员"
              : store.manager
                ? "团队负责人"
                : "团队成员"
          }}
        </p>
        <div class="divider" />
        <div class="actions">
          <button
            @click="
              dialog = {
                title: '修改密码',
                path: '/auth/password',
                method: 'POST',
                fields: [
                  {
                    key: 'old_password',
                    label: '原密码',
                    type: 'password',
                    required: true,
                  },
                  {
                    key: 'new_password',
                    label: '新密码',
                    type: 'password',
                    required: true,
                  },
                ],
              }
            "
          >
            修改密码</button
          ><button v-if="store.admin" @click="invite">生成管理员邀请码</button
          ><button class="danger" @click="store.logout">退出登录</button>
        </div>
        <div v-if="code" class="success-box">
          <b>管理员邀请码（仅本次显示）</b>
          <p>{{ code }}</p>
          <small>有效期至 {{ expiry }}，单次使用。</small>
        </div>
      </div>
    </section>
    <ModelConfigurationForm />
  </div>
  <SchedulingPolicyForm />
  <template v-if="store.admin"
    ><section class="panel section-gap">
      <div class="panelhead">
        <h2>会议室</h2>
        <button class="primary small" @click="room()">＋ 添加会议室</button>
      </div>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>名称</th>
              <th>容量</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in store.data?.rooms" :key="r.id">
              <td>{{ r.name }}</td>
              <td>{{ r.capacity }} 人</td>
              <td>
                <button class="small" @click="room(r)">编辑</button
                ><button
                  class="small danger"
                  @click="
                    dialog = {
                      title: '删除会议室',
                      path: '/rooms/' + r.id,
                      method: 'DELETE',
                      fields: [],
                    }
                  "
                >
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section class="panel section-gap">
      <div class="panelhead">
        <h2>操作日志</h2>
        <button @click="audit">读取日志</button>
      </div>
      <div class="panelbody">
        <p v-if="!logs.length" class="muted">点击查看最近200条操作记录。</p>
        <div v-for="(row, i) in logs" :key="i" class="notice">
          <b>{{ actionName(row.action) }}</b
          ><small
            >{{ row.actor_name || "系统" }} ·
            {{ row.created_at.replace("T", " ").slice(0, 19) }}</small
          >
        </div>
      </div>
    </section></template
  >
  <div class="formerror">{{ error }}</div>
  <EntityDialog v-if="dialog" v-bind="dialog" @close="dialog = null" />
</template>
