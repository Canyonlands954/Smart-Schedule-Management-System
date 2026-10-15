<script setup lang="ts">
import { ref, computed } from "vue";
import { useRouter } from "vue-router";
import { api } from "../services/api";
import { useWorkspace } from "../stores/workspace";
const store = useWorkspace(),
  router = useRouter(),
  mode = ref(store.initialized ? "login" : "admin"),
  name = ref(""),
  username = ref(""),
  password = ref(""),
  confirm = ref(""),
  invite = ref(""),
  error = ref(""),
  busy = ref(false);
const registering = computed(() => mode.value !== "login");
async function submit() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    if (registering.value) {
      if (password.value !== confirm.value)
        throw new Error("两次密码输入不一致");
      await api("/auth/register", "POST", {
        name: name.value,
        username: username.value,
        password: password.value,
        confirm: confirm.value,
        role: mode.value === "admin" ? "admin" : "member",
        invite_code: invite.value,
      });
    }
    await store.login(username.value, password.value);
    await router.replace("/dashboard");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作失败";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="loginwrap">
    <section class="login-story">
      <div class="brand">
        <span class="brandmark">序</span>
        <div>序时<span class="brand-en">SMART SCHEDULE</span></div>
      </div>
      <div class="story-copy">
        <span class="eyebrow">把每一项安排，落到正确的人</span>
        <h1>一句安排，<br />有序协作。</h1>
        <p>个人日程 · 智能分配 · 团队协作</p>
        <div class="story-card">
          <span class="tag">安排示例</span>
          <p>让三组的小赵下午三点到5号会议室开会，带上他们组的所有人。</p>
          <div class="story-rule">只写入小赵的日程，由小赵组织组员。</div>
        </div>
      </div>
      <small>企业日程工作空间 · 共享数据，独立账号</small>
    </section>
    <div class="loginbox">
      <div class="auth-tabs">
        <button
          v-for="item in [
            ['login', '登录'],
            ['member', '用户注册'],
            ['admin', '管理员注册'],
          ]"
          :key="item[0]"
          :class="{ selected: mode === item[0] }"
          @click="
            mode = item[0];
            error = '';
          "
        >
          {{ item[1] }}
        </button>
      </div>
      <span class="tag">{{ store.initialized ? "欢迎回来" : "首次使用" }}</span>
      <h1>
        {{
          !registering
            ? "登录工作空间"
            : mode === "admin"
              ? "注册管理员"
              : "注册个人账号"
        }}
      </h1>
      <p>
        {{
          !store.initialized
            ? "请在服务器本机注册首位管理员。"
            : registering
              ? "填写账号信息，加入工作空间。"
              : "使用你的账号与密码登录。"
        }}
      </p>
      <form class="form" @submit.prevent="submit">
        <label v-if="registering"
          >姓名<input
            v-model="name"
            name="name"
            required
            maxlength="50" /></label
        ><label
          >账号<input
            v-model="username"
            name="username"
            autocomplete="username"
            required
            pattern="[a-zA-Z0-9_.-]{3,50}"
            placeholder="3–50位字母、数字或下划线" /></label
        ><label
          >密码<input
            v-model="password"
            name="password"
            type="password"
            :autocomplete="registering ? 'new-password' : 'current-password'"
            :minlength="registering ? 10 : undefined"
            maxlength="128"
            required /></label
        ><label v-if="registering"
          >确认密码<input
            v-model="confirm"
            name="confirm"
            type="password"
            autocomplete="new-password"
            minlength="10"
            required /></label
        ><label v-if="registering && mode === 'admin' && store.initialized"
          >管理员邀请码<input v-model="invite" name="invite_code" required
        /></label>
        <div class="formerror" aria-live="polite">{{ error }}</div>
        <button class="primary widebtn" :disabled="busy" :aria-busy="busy">
          {{ registering ? "注册并登录" : "登录" }} →
        </button>
      </form>
      <p class="loginhint">
        {{
          registering
            ? "密码至少10位；管理员邀请码24小时内单次有效。"
            : "忘记密码时，请联系管理员重置。"
        }}
      </p>
    </div>
  </div>
</template>
