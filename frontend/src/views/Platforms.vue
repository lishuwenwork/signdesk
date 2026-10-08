<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, report, periodic, confirm, execute } from '../api'
import RequestDialog from '../components/RequestDialog.vue'
import RequestTemplatesDialog from '../components/RequestTemplatesDialog.vue'
const platforms = ref([]),
  selectedId = ref(''),
  error = ref('')
const selected = computed(() => platforms.value.find((p) => p.id === selectedId.value))
const platformDialog = ref(false),
  accountDialog = ref(false),
  saving = ref(false)
const platformForm = reactive({ id: '', name: '', note: '', enabled: true, version: 1 })
const accountForm = reactive({ id: '', alias: '', enabled: true, version: 1 })
const requestDialog = reactive({ visible: false, mode: 'new', platformId: '', accountId: '', request: null })
const templatesDialog = reactive({ visible: false, platformId: '', seed: null })
async function load() {
  try {
    platforms.value = await api('/platforms')
    if (!platforms.value.some((p) => p.id === selectedId.value))
      selectedId.value = platforms.value[0]?.id || ''
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}
periodic(load, 4000)
function editPlatform(p) {
  Object.assign(
    platformForm,
    p ? { ...p, enabled: !!p.enabled } : { id: '', name: '', note: '', enabled: true, version: 1 },
  )
  platformDialog.value = true
}
async function savePlatform() {
  if (!platformForm.name.trim()) return ElMessage.warning('请填写平台名称')
  saving.value = true
  try {
    const result = await api(
      platformForm.id ? `/platforms/${platformForm.id}` : '/platforms',
      platformForm.id ? 'PUT' : 'POST',
      platformForm,
    )
    if (result.id) selectedId.value = result.id
    platformDialog.value = false
    await load()
    ElMessage.success('平台已保存')
  } catch (e) {
    report(e)
  } finally {
    saving.value = false
  }
}
function editAccount(a) {
  Object.assign(
    accountForm,
    a ? { ...a, enabled: !!a.enabled } : { id: '', alias: '', enabled: true, version: 1 },
  )
  accountDialog.value = true
}
async function saveAccount() {
  if (!accountForm.alias.trim()) return ElMessage.warning('请填写账号别名')
  saving.value = true
  try {
    await api(
      accountForm.id ? `/accounts/${accountForm.id}` : `/platforms/${selectedId.value}/accounts`,
      accountForm.id ? 'PUT' : 'POST',
      accountForm,
    )
    accountDialog.value = false
    await load()
    ElMessage.success('账号已保存')
  } catch (e) {
    report(e)
  } finally {
    saving.value = false
  }
}
async function remove(kind, item) {
  const label = {
    platforms: '平台和所有账号、请求、接口模板、计划及记录',
    accounts: '账号和所有请求及记录',
    requests: '请求和其历史记录',
  }[kind]
  if (!(await confirm(`删除 ${item.name || item.alias}？将同时删除${label}。`))) return
  try {
    await api(`/${kind}/${item.id}`, 'DELETE', {})
    await load()
    ElMessage.success('已删除')
  } catch (e) {
    report(e)
  }
}
async function toggleRequest(request) {
  try {
    await api(`/requests/${request.id}`, 'PUT', { ...request, enabled: !request.enabled })
    await load()
  } catch (e) {
    report(e)
  }
}
function openRequest(accountId, mode = 'new', request = null) {
  Object.assign(requestDialog, { platformId: selectedId.value, accountId, mode, request, visible: true })
}
function openTemplates(request = null) {
  Object.assign(templatesDialog, {
    platformId: selectedId.value,
    seed: request ? { name: request.name, rules: request.rules } : null,
    visible: true,
  })
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>平台与账号</h1>
      <div class="muted">每个账号保留自己的完整请求，支持不同的鉴权和参数结构。</div>
    </div>
    <el-button type="primary" @click="editPlatform()">＋ 新增平台</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-empty v-if="!platforms.length && !error" description="从第一个平台开始，让签到集中管理"
    ><el-button type="primary" @click="editPlatform()">新增平台</el-button></el-empty
  >
  <div v-if="platforms.length" class="platform-layout">
    <aside class="panel platform-list">
      <button
        v-for="p in platforms"
        :key="p.id"
        class="platform-choice"
        :class="{ selected: selectedId === p.id }"
        @click="selectedId = p.id"
      >
        <strong>{{ p.name }} <el-tag v-if="!p.enabled" size="small" type="info">停用</el-tag></strong
        ><small
          >{{ p.accounts.length }} 个账号 ·
          {{ p.accounts.reduce((sum, a) => sum + a.requests.length, 0) }} 个请求</small
        >
      </button>
    </aside>
    <section v-if="selected">
      <div class="panel">
        <div class="panel-head" style="margin-bottom: 8px">
          <div>
            <h2>{{ selected.name }}</h2>
            <div class="muted">{{ selected.note || '此平台下的账号按独立 cURL 发送请求' }}</div>
          </div>
          <div class="actions">
            <el-button size="small" @click="editPlatform(selected)">编辑平台</el-button
            ><el-button size="small" type="danger" plain @click="remove('platforms', selected)"
              >删除</el-button
            >
          </div>
        </div>
        <div class="actions space-top">
          <el-button type="primary" :disabled="!selected.enabled" @click="execute('platform', selected.id)"
            >执行此平台</el-button
          ><el-button @click="editAccount()">＋ 添加账号</el-button
          ><el-button @click="openTemplates()">接口模板</el-button
          ><RouterLink to="/schedules"><el-button>设置计划</el-button></RouterLink>
        </div>
      </div>
      <el-empty v-if="!selected.accounts.length" description="添加账号，再粘贴完整 cURL"
        ><el-button @click="editAccount()">添加账号</el-button></el-empty
      >
      <div v-for="account in selected.accounts" :key="account.id" class="account-card">
        <div class="account-heading">
          <div class="account-title">
            <span class="avatar">{{ account.alias.slice(0, 1) }}</span>
            <h3>{{ account.alias }}</h3>
            <el-tag v-if="!account.enabled" size="small" type="info">已停用</el-tag>
          </div>
          <div class="actions">
            <el-button size="small" @click="openRequest(account.id)">＋ 添加请求</el-button
            ><el-button size="small" @click="editAccount(account)">编辑账号</el-button
            ><el-button size="small" text type="danger" @click="remove('accounts', account)">删除</el-button>
          </div>
        </div>
        <div v-for="request in account.requests" :key="request.id" class="request-row">
          <div class="request-info">
            <div class="request-name">
              <strong>{{ request.name }}</strong
              ><el-tag v-if="request.authPaused" type="danger" size="small">需更新 cURL</el-tag
              ><el-tag v-else-if="!request.enabled" type="info" size="small">已停用</el-tag>
            </div>
            <div class="request-meta">
              <span class="method mono">{{ request.method }}</span
              ><span>{{ request.safeHost || '尚未导入请求内容' }}</span
              ><span>版本 {{ request.currentRevision }}</span>
            </div>
          </div>
          <div class="actions">
            <el-switch
              :model-value="!!request.enabled"
              @change="toggleRequest(request)"
              :aria-label="`${request.name}启用状态`"
              size="small"
            /><el-button
              size="small"
              :disabled="!selected.enabled || !account.enabled || !request.enabled || !!request.authPaused"
              @click="execute('request', request.id)"
              >执行</el-button
            ><el-dropdown trigger="click"
              ><el-button size="small">更多 ▾</el-button
              ><template #dropdown
                ><el-dropdown-menu
                  ><el-dropdown-item @click="openRequest(account.id, 'update', request)"
                    >更新 cURL</el-dropdown-item
                  ><el-dropdown-item @click="openRequest(account.id, 'rules', request)"
                    >编辑名称 / 规则</el-dropdown-item
                  ><el-dropdown-item @click="openTemplates(request)"
                    >保存为接口模板</el-dropdown-item
                  ><el-dropdown-item @click="openRequest(account.id, 'view', request)"
                    >查看完整请求</el-dropdown-item
                  ><el-dropdown-item
                    :disabled="
                      !request.enabled || !!request.authPaused || !account.enabled || !selected.enabled
                    "
                    @click="execute('request', request.id, true)"
                    >重新执行（忽略当日标记）</el-dropdown-item
                  ><el-dropdown-item divided @click="remove('requests', request)"
                    >删除请求</el-dropdown-item
                  ></el-dropdown-menu
                ></template
              ></el-dropdown
            >
          </div>
        </div>
        <div v-if="!account.requests.length" class="muted" style="padding-top: 10px">
          还没有请求。点击「添加请求」粘贴浏览器复制的 cURL。
        </div>
      </div>
      <div class="inline-info">
        列表仅显示主机和方法，不显示凭证。更新 cURL 会创建新版本；已经排队的任务仍使用其冻结版本。
      </div>
    </section>
  </div>
  <el-dialog v-model="platformDialog" :title="platformForm.id ? '编辑平台' : '新增平台'" width="470px"
    ><el-form label-position="top"
      ><el-form-item label="平台名称"
        ><el-input v-model="platformForm.name" maxlength="40" placeholder="例如：平台 A" /></el-form-item
      ><el-form-item label="备注"
        ><el-input v-model="platformForm.note" type="textarea" :rows="3" maxlength="500" /></el-form-item
      ><el-form-item label="启用平台"><el-switch v-model="platformForm.enabled" /></el-form-item></el-form
    ><template #footer
      ><el-button @click="platformDialog = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="savePlatform">保存平台</el-button></template
    ></el-dialog
  >
  <el-dialog v-model="accountDialog" :title="accountForm.id ? '编辑账号' : '添加账号'" width="450px"
    ><el-form label-position="top"
      ><el-form-item label="账号别名"
        ><el-input v-model="accountForm.alias" maxlength="40" placeholder="例如：主账号" /></el-form-item
      ><el-form-item label="启用账号"><el-switch v-model="accountForm.enabled" /></el-form-item
    ></el-form>
    <div class="muted">别名用于区分账号，鉴权信息直接从完整 cURL 保存。</div>
    <template #footer
      ><el-button @click="accountDialog = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveAccount">保存账号</el-button></template
    ></el-dialog
  >
  <RequestDialog
    v-model="requestDialog.visible"
    :mode="requestDialog.mode"
    :platform-id="requestDialog.platformId"
    :account-id="requestDialog.accountId"
    :request="requestDialog.request"
    @saved="load"
  />
  <RequestTemplatesDialog
    v-model="templatesDialog.visible"
    :platform-id="templatesDialog.platformId"
    :seed="templatesDialog.seed"
  />
</template>
