<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { api } from "../services/api";
import { useWorkspace } from "../stores/workspace";
interface Config {
  base_url: string;
  model: string;
  has_key: boolean;
  enabled: boolean;
  encrypted: boolean;
  source: string;
  revision: number;
  updated_at: string;
}
const store = useWorkspace(),
  config = ref<Config | null>(null),
  form = reactive({ base_url: "", model: "", api_key: "", enabled: true }),
  busy = ref(false),
  error = ref(""),
  message = ref(""),
  confirmClear = ref(false);
function apply(value: Config) {
  config.value = value;
  form.base_url = value.base_url;
  form.model = value.model;
  form.enabled = value.has_key ? value.enabled : true;
  form.api_key = "";
  confirmClear.value = false;
}
async function load() {
  busy.value = true;
  error.value = "";
  try {
    apply((await api<{ config: Config }>("/admin/model-config")).config);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取配置失败";
  } finally {
    busy.value = false;
  }
}
onMounted(() => {
  if (store.admin) void load();
});
async function save() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    apply(
      (
        await api<{ config: Config }>("/admin/model-config", "PUT", {
          ...form,
          revision: config.value?.revision,
        })
      ).config,
    );
    await store.refresh();
    message.value = "配置已加密保存，后续管理员请求立即使用新配置。";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "保存失败";
  } finally {
    busy.value = false;
  }
}
async function test() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    message.value = (
      await api<{ message: string }>("/admin/model-config/test", "POST", {
        revision: config.value?.revision,
      })
    ).message;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "连接测试失败";
  } finally {
    busy.value = false;
  }
}
async function clear() {
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    apply(
      (
        await api<{ config: Config }>("/admin/model-config", "DELETE", {
          revision: config.value?.revision,
        })
      ).config,
    );
    await store.refresh();
    message.value = "配置已清除，模型接口已停用。";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "清除失败";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="panel">
    <div class="panelhead">
      <h2>模型接口设置</h2>
      <span class="tag">仅管理员</span>
    </div>
    <div class="panelbody">
      <template v-if="store.admin">
        <p class="muted">
          支持 OpenAI
          兼容接口。地址、模型名称和密钥在服务器加密保存，所有管理员共享此配置；密钥不回显。
        </p>
        <form
          v-if="config"
          class="form model-config-form"
          @submit.prevent="save"
        >
          <fieldset :disabled="busy" class="ai-draft-fields">
            <div class="model-config-status">
              <span class="tag">{{
                config.has_key
                  ? config.enabled
                    ? "已启用"
                    : "已停用"
                  : "尚未配置"
              }}</span
              ><small v-if="config.has_key">{{
                config.encrypted
                  ? "已加密保存"
                  : "使用服务器预置配置，保存后加密"
              }}</small
              ><button type="button" class="small" @click="load">
                重新读取
              </button>
            </div>
            <label
              >接口基础地址<input
                v-model="form.base_url"
                aria-label="接口基础地址"
                aria-describedby="model-base-help"
                type="url"
                required
                maxlength="500"
                autocomplete="off"
                placeholder="https://api.example.com/v1"
              /><small id="model-base-help" class="field-help"
                >填写HTTPS基础地址，不含
                /chat/completions；本机接口支持HTTP。</small
              ></label
            >
            <label
              >模型名称<input
                v-model="form.model"
                aria-label="模型名称"
                required
                maxlength="100"
                autocomplete="off"
                placeholder="填写服务商提供的模型名称"
            /></label>
            <label
              >API 密钥<input
                v-model="form.api_key"
                aria-label="API 密钥"
                aria-describedby="model-key-help"
                type="password"
                :required="!config.has_key"
                maxlength="4096"
                autocomplete="new-password"
                :spellcheck="false"
                :placeholder="
                  config.has_key
                    ? '密钥已保存，留空保持原密钥'
                    : '首次设置请填写密钥'
                "
              /><small id="model-key-help" class="field-help"
                >更换密钥时填入新值；保存后输入框清空。</small
              ></label
            >
            <label class="model-enabled"
              ><input
                v-model="form.enabled"
                type="checkbox"
              />启用模型接口（仅管理员可调用）</label
            >
            <div class="actions model-config-actions">
              <button class="primary" :aria-busy="busy">保存配置</button
              ><button
                type="button"
                :disabled="!config.has_key || !config.enabled"
                @click="test"
              >
                测试已保存的连接</button
              ><button
                v-if="config.has_key"
                type="button"
                class="danger"
                @click="confirmClear = true"
              >
                清除配置
              </button>
            </div>
            <small class="field-help"
              >连接测试只发送固定测试消息，不发送团队或日程内容；可能产生服务商调用费用。</small
            >
            <div v-if="confirmClear" class="warning-box">
              <p>清除后需要重新填写密钥，模型接口将停用。</p>
              <div class="actions">
                <button type="button" class="danger" @click="clear">
                  确认清除并停用</button
                ><button type="button" @click="confirmClear = false">
                  取消
                </button>
              </div>
            </div>
          </fieldset>
        </form>
        <button v-else type="button" :disabled="busy" @click="load">
          重新读取配置
        </button>
        <div v-if="message" class="success-box" role="status">
          {{ message }}
        </div>
        <div class="formerror" aria-live="polite">{{ error }}</div>
      </template>
      <p v-else class="settings-copy">
        模型接口仅限管理员配置和调用。团队负责人使用本地规则解析起草安排，其他日程功能保持可用。
      </p>
    </div>
  </section>
</template>
