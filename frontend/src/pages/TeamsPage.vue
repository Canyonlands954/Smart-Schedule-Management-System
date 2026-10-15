<script setup lang="ts">
import { ref, computed } from "vue";
import type { User, Team } from "../domain/types";
import { api } from "../services/api";
import { useWorkspace } from "../stores/workspace";
import PageHead from "../components/PageHead.vue";
import Icon from "../components/Icon.vue";
import EntityDialog, { type EntityField } from "../components/EntityDialog.vue";
const store = useWorkspace(),
  query = ref(""),
  teamFilter = ref("all"),
  busyRows = ref<{ name: string; start: string; end: string }[] | null>(null),
  error = ref(""),
  dialog = ref<{
    title: string;
    path: string;
    method: string;
    fields: EntityField[];
    initial?: Record<string, unknown>;
  } | null>(null);
const users = computed(
  () =>
    store.data?.users
      .filter(
        (u) => u.name.includes(query.value) || u.username.includes(query.value),
      )
      .filter(
        (u) =>
          teamFilter.value === "all" || u.team_id === Number(teamFilter.value),
      ) ?? [],
);
function team(t?: Team) {
  dialog.value = {
    title: t ? "编辑团队" : "新建团队",
    path: "/teams" + (t ? "/" + t.id : ""),
    method: t ? "PUT" : "POST",
    fields: [
      { key: "name", label: "团队名称", required: true },
      { key: "description", label: "描述", type: "textarea" },
      ...(t
        ? [
            {
              key: "leader_id",
              label: "负责人",
              options: [
                { value: null, label: "未设置" },
                ...(store.data?.users
                  .filter((u) => u.team_id === t.id && u.active)
                  .map((u) => ({ value: u.id, label: u.name })) ?? []),
              ],
            },
          ]
        : []),
    ],
    initial: t ? { ...t } : {},
  };
}
function user(u?: User) {
  dialog.value = {
    title: u ? "编辑成员" : "添加成员",
    path: "/users" + (u ? "/" + u.id : ""),
    method: u ? "PUT" : "POST",
    fields: [
      ...(u ? [] : [{ key: "username", label: "登录账号", required: true }]),
      { key: "name", label: "姓名", required: true },
      {
        key: "password",
        label: u ? "重置密码（留空保持不变）" : "初始密码",
        type: "password",
        required: !u,
      },
      {
        key: "role",
        label: "角色",
        options: [
          { value: "member", label: "成员" },
          { value: "admin", label: "管理员" },
        ],
      },
      {
        key: "team_id",
        label: "团队",
        options: [
          { value: null, label: "未分组" },
          ...(store.data?.teams.map((t) => ({ value: t.id, label: t.name })) ??
            []),
        ],
      },
      ...(u
        ? [
            {
              key: "active",
              label: "状态",
              options: [
                { value: 1, label: "启用" },
                { value: 0, label: "停用" },
              ],
            },
          ]
        : []),
    ],
    initial: {
      ...u,
      password: "",
      role: u?.role ?? "member",
      team_id: u?.team_id ?? null,
    },
  };
}
async function busy(t: Team) {
  try {
    busyRows.value = (
      await api<{ busy: { name: string; start: string; end: string }[] }>(
        "/team-busy?team_id=" + t.id,
      )
    ).busy;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取失败";
  }
}
</script>
<template>
  <PageHead
    title="团队管理"
    description="组织成员、明确负责人，让协作从清晰的分工开始"
    ><button v-if="store.admin" class="primary" @click="team()">
      ＋ 新建团队
    </button></PageHead
  >
  <div class="teamcards">
    <section v-for="t in store.data?.teams" :key="t.id" class="panel teamcard">
      <div class="teamcard-top">
        <div class="symbol"><Icon name="users" /></div>
        <span class="tag gray"
          >{{
            store.data?.users.filter((u) => u.team_id === t.id && u.active)
              .length
          }}
          位成员</span
        >
      </div>
      <h2>{{ t.name }}</h2>
      <p>{{ t.description }}</p>
      <div class="team-metrics">
        负责人 ·
        {{
          store.data?.users.find((u) => u.id === t.leader_id)?.name ??
          "尚未设置"
        }}
      </div>
      <div class="avatars">
        <span
          v-for="u in store.data?.users
            .filter((u) => u.team_id === t.id)
            .slice(0, 5)"
          :key="u.id"
          class="avatar"
          >{{ u.name[0] }}</span
        >
      </div>
      <div class="actions">
        <button
          v-if="store.admin || t.id === store.user?.team_id"
          class="small"
          @click="busy(t)"
        >
          忙闲日程</button
        ><template v-if="store.admin"
          ><button class="small" @click="team(t)">编辑</button
          ><button
            class="small danger"
            @click="
              dialog = {
                title: '删除团队',
                path: '/teams/' + t.id,
                method: 'DELETE',
                fields: [],
              }
            "
          >
            删除
          </button></template
        >
      </div>
    </section>
  </div>
  <section class="panel">
    <div class="panelhead">
      <h2>成员目录</h2>
      <button v-if="store.admin" class="primary small" @click="user()">
        ＋ 添加成员
      </button>
    </div>
    <div class="directory-toolbar">
      <input
        v-model="query"
        type="search"
        placeholder="查找姓名或账号"
        aria-label="查找成员"
      /><select v-model="teamFilter" aria-label="筛选团队">
        <option value="all">全部团队</option>
        <option v-for="t in store.data?.teams" :key="t.id" :value="t.id">
          {{ t.name }}
        </option></select
      ><small>{{ users.length }} 位成员</small>
    </div>
    <div class="table-scroll">
      <table>
        <thead>
          <tr>
            <th>成员</th>
            <th>所属团队</th>
            <th>角色</th>
            <th>状态</th>
            <th v-if="store.admin">管理</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.id">
            <td>
              <div class="member-identity">
                <span class="avatar">{{ u.name[0] }}</span>
                <div>
                  {{ u.name }}<small class="block">{{ u.username }}</small>
                </div>
              </div>
            </td>
            <td>
              {{
                store.data?.teams.find((t) => t.id === u.team_id)?.name ??
                "未分组"
              }}
            </td>
            <td>
              {{
                u.role === "admin"
                  ? "管理员"
                  : store.data?.teams.some((t) => t.leader_id === u.id)
                    ? "团队负责人"
                    : "成员"
              }}
            </td>
            <td>
              <span class="tag" :class="u.active ? '' : 'gray'">{{
                u.active ? "启用" : "停用"
              }}</span>
            </td>
            <td v-if="store.admin">
              <button class="small" @click="user(u)">编辑</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="!users.length" class="empty">没有找到匹配成员</div>
  </section>
  <div class="formerror">{{ error }}</div>
  <div v-if="busyRows" class="vue-dialog" role="dialog" aria-modal="true">
    <section class="panel">
      <div class="modalhead">
        <h3>团队忙闲</h3>
        <button aria-label="关闭对话框" @click="busyRows = null">×</button>
      </div>
      <p v-for="(b, i) in busyRows" :key="i" class="detail-note">
        {{ b.name }} · {{ b.start.replace("T", " ") }} —
        {{ b.end.replace("T", " ") }}
      </p>
      <div v-if="!busyRows.length" class="empty">暂无占用时段</div>
    </section>
  </div>
  <EntityDialog v-if="dialog" v-bind="dialog" @close="dialog = null" />
</template>
