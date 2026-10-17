<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { api } from "../services/api";
interface Config {
  base_url: string;
  model: string;
  dimensions: number;
  enabled: boolean;
  has_key: boolean;
  revision: number;
  space_id: string;
  encrypted: boolean;
}
const config = ref<Config | null>(null),
  busy = ref(false),
  error = ref(""),
  message = ref(""),
  confirmClear = ref(false);
const form = reactive({
  base_url: "",
  model: "",
  dimensions: 1024,
  api_key: "",
  enabled: true,
});
function apply(c: Config) {
  config.value = c;
  Object.assign(form, {
    base_url: c.base_url,
    model: c.model,
    dimensions: c.dimensions,
    enabled: c.has_key ? c.enabled : true,
    api_key: "",
  });
  confirmClear.value = false;
}
async function load() {
  try {
    apply((await api<{ config: Config }>("/admin/embedding-config")).config);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取失败";
  }
}
onMounted(load);
async function perform(action: "save" | "test" | "clear") {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    if (action === "test")
      message.value = (
        await api<{ message: string }>("/admin/embedding-config/test", "POST", {
          revision: config.value?.revision,
        })
      ).message;
    else {
      apply(
        (
          await api<{ config: Config }>(
            "/admin/embedding-config",
            action === "save" ? "PUT" : "DELETE",
            action === "save"
              ? { ...form, revision: config.value?.revision }
              : { revision: config.value?.revision },
          )
        ).config,
      );
      message.value =
        action === "clear"
          ? "向量模型已清除。"
          : "向量配置已加密保存。更换模型或维度后，请对资料执行重新处理。";
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作失败";
  } finally {
    form.api_key = "";
    busy.value = false;
  }
}
</script>
<template>
  <section class="panel section-gap knowledge-editor">
    <div class="panelhead">
      <h2>知识库向量模型</h2>
      <span class="tag">{{ config?.enabled ? "已启用" : "未启用" }}</span>
    </div>
    <form class="form" @submit.prevent="perform('save')">
      <p class="muted">
        与问答模型独立配置。仅管理员可使用接口；处理资料需要逐份授权发送。未配置时仍可进行关键词检索。
      </p>
      <label
        >向量接口基础地址<input
          v-model="form.base_url"
          required
          type="url"
          maxlength="500"
          placeholder="https://模型服务地址/v1"
          :disabled="busy"
      /></label>
      <div class="two">
        <label
          >向量模型名称<input
            v-model="form.model"
            required
            maxlength="100"
            :disabled="busy" /></label
        ><label
          >模型实际返回维度<input
            v-model.number="form.dimensions"
            type="number"
            min="1"
            max="4096"
            required
            :disabled="busy"
        /></label>
      </div>
      <label
        >向量 API 密钥<input
          v-model="form.api_key"
          type="password"
          autocomplete="new-password"
          maxlength="4096"
          :placeholder="
            config?.has_key ? '已安全保存，留空沿用' : '首次配置需填写'
          "
          :required="!config?.has_key"
          :disabled="busy"
      /></label>
      <label class="knowledge-check"
        ><input
          v-model="form.enabled"
          type="checkbox"
          :disabled="busy"
        />启用向量检索</label
      >
      <p v-if="config?.encrypted" class="muted">
        配置已加密保存，页面不返回密钥。
      </p>
      <div class="actions">
        <button class="primary" :disabled="busy || !config">保存向量配置</button
        ><button
          type="button"
          :disabled="busy || !config?.enabled"
          @click="perform('test')"
        >
          测试已保存配置</button
        ><button
          type="button"
          :disabled="busy || !config?.has_key"
          @click="confirmClear = true"
        >
          清除向量配置</button
        ><button type="button" :disabled="busy" @click="load">重新读取</button>
      </div>
      <div v-if="confirmClear" class="warning-box">
        清除后保留已处理资料，检索将使用关键词。
        <div class="actions">
          <button type="button" @click="confirmClear = false">取消</button
          ><button
            type="button"
            class="danger"
            :disabled="busy"
            @click="perform('clear')"
          >
            确认清除
          </button>
        </div>
      </div>
      <p v-if="error" class="formerror" role="alert">{{ error }}</p>
      <p v-if="message" class="success-box" role="status">{{ message }}</p>
    </form>
  </section>
</template>
