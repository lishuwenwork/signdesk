<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, confirm, dateTime } from '../api'
import { readBackupFile } from '../backupFile.mjs'
import { cloneSettings, settingsDirty, validateSettings } from '../settingsDraft.mjs'
import DeskIcon from '../components/DeskIcon.vue'

const route = useRoute(), router = useRouter()
const categories = [['execution', '执行偏好'], ['proxy', '网络代理'], ['backup', '配置备份'], ['about', '关于系统']]
const tab = computed(() => categories.some(([key]) => key === route.query.tab) ? route.query.tab : 'execution')
const form = ref(null), saved = ref(null), loading = ref(true), saving = ref(false), settingsError = ref(''), conflict = ref(false)
const dirty = computed(() => settingsDirty(form.value, saved.value))
const includeRequests = ref(false), importFile = ref(null), preview = ref(null), fileInput = ref(null), fileName = ref('')
const backupBusy = ref(false), importing = ref(false), backupError = ref(''), importSuccess = ref(false)
const confirmVisible = ref(false), replaceConfirmed = ref(false)
const system = ref(null), systemLoading = ref(false), systemError = ref('')
let disposed = false, settingsSequence = 0, importSession = 0, exportSession = 0, systemSequence = 0, consentSession = 0

async function load() {
  const current = ++settingsSequence
  loading.value = true
  settingsError.value = ''
  try {
    const data = await api('/settings')
    if (disposed || current !== settingsSequence) return false
    form.value = cloneSettings(data)
    saved.value = cloneSettings(data)
    conflict.value = false
    return true
  } catch (e) {
    if (!disposed && current === settingsSequence) settingsError.value = e.message
    return false
  } finally { if (!disposed && current === settingsSequence) loading.value = false }
}
async function reload() {
  if (saving.value || loading.value || importing.value) return
  const current = settingsSequence
  if (dirty.value && !(await confirm('重新载入会放弃未保存的执行偏好与代理修改，是否继续？'))) return
  if (!disposed && current === settingsSequence) await load()
}
onMounted(() => { load(); window.addEventListener('beforeunload', beforeUnload) })
function beforeUnload(event) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}
onBeforeRouteUpdate(() => {
  if (!importing.value) return true
  ElMessage.info('正在整体替换配置，请等待完成后再离开。')
  return false
})
onBeforeRouteLeave(async () => {
  if (importing.value) {
    ElMessage.info('正在整体替换配置，请等待完成后再离开。')
    return false
  }
  if (!dirty.value) return true
  return await confirm('执行偏好或代理仍有未保存的修改。离开会放弃这些表单修改，不影响已保存配置。是否放弃并离开？')
})
function clearBackup() {
  importSession++
  exportSession++
  importFile.value = null
  preview.value = null
  fileName.value = ''
  backupError.value = ''
  backupBusy.value = false
  importing.value = false
  confirmVisible.value = false
  replaceConfirmed.value = false
  if (fileInput.value) fileInput.value.value = ''
}
onUnmounted(() => {
  disposed = true
  settingsSequence++
  systemSequence++
  clearBackup()
  form.value = null
  saved.value = null
  window.removeEventListener('beforeunload', beforeUnload)
})
watch(tab, (next, previous) => {
  if (previous === 'backup' && next !== 'backup') clearBackup()
  if (next === 'about') loadSystem()
  else { systemSequence++; systemLoading.value = false }
}, { immediate: true })
function changeTab(key) { router.replace({ path: '/settings', query: { ...route.query, tab: key } }) }
function tabKeydown(event, index) {
  const direction = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
  if (!direction && !['Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const target = event.key === 'Home' ? 0 : event.key === 'End' ? categories.length - 1 : (index + direction + categories.length) % categories.length
  changeTab(categories[target][0])
  event.currentTarget.parentElement.querySelectorAll('[role="tab"]')[target]?.focus()
}
async function save() {
  if (!form.value || loading.value || saving.value || importing.value || conflict.value) return
  settingsError.value = validateSettings(form.value)
  if (settingsError.value) return
  const current = settingsSequence, data = cloneSettings(form.value)
  data.proxy.host = data.proxy.host.trim()
  saving.value = true
  try {
    await api('/settings', 'PUT', data)
    if (disposed || current !== settingsSequence) return
    // The write succeeded: do not leave a stale editable version if the follow-up read fails.
    form.value = null
    saved.value = null
    if (await load()) ElMessage.success('设置已保存')
    else if (!disposed) settingsError.value = `设置已保存，但读取最新配置失败：${settingsError.value}`
  } catch (e) {
    if (disposed || current !== settingsSequence) return
    conflict.value = e.status === 409
    settingsError.value = conflict.value ? '设置版本已变化，本次未覆盖。未保存草稿仍保留，请核对后重新载入最新配置。' : e.message
  } finally { if (!disposed) saving.value = false }
}
async function exportBackup() {
  if (backupBusy.value || tab.value !== 'backup') return
  const current = ++exportSession, full = includeRequests.value
  backupBusy.value = true
  backupError.value = ''
  try {
    const data = await api('/backups/export', 'POST', { includeRequests: full })
    if (disposed || current !== exportSession || tab.value !== 'backup') return
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob), link = document.createElement('a')
    try {
      link.href = url
      link.download = `signdesk-${full ? 'full-backup' : 'config'}-${Date.now()}.json`
      link.hidden = true
      document.body.append(link)
      link.click()
    } finally { link.remove(); URL.revokeObjectURL(url) }
    ElMessage.success('明文 JSON 已导出，请妥善保管')
  } catch (e) {
    if (!disposed && current === exportSession) backupError.value = e.message
  } finally { if (!disposed && current === exportSession) backupBusy.value = false }
}
async function chooseFile(event) {
  if (importing.value || disposed || tab.value !== 'backup') return
  const current = ++importSession
  preview.value = null
  importFile.value = null
  confirmVisible.value = false
  replaceConfirmed.value = false
  backupError.value = ''
  const file = event.target.files?.[0]
  fileName.value = file?.name ?? ''
  if (!file) { backupBusy.value = false; return }
  backupBusy.value = true
  try {
    const backup = await readBackupFile(file)
    if (!disposed && current === importSession) importFile.value = backup
  } catch (e) {
    if (!disposed && current === importSession) backupError.value = e.message
  } finally { if (!disposed && current === importSession) backupBusy.value = false }
}
async function previewImport() {
  if (!importFile.value || backupBusy.value || tab.value !== 'backup') return
  const current = importSession
  preview.value = null
  backupError.value = ''
  backupBusy.value = true
  try {
    const result = await api('/backups/preview', 'POST', { backup: importFile.value })
    if (!disposed && current === importSession) preview.value = result
  } catch (e) {
    if (!disposed && current === importSession) backupError.value = e.message
  } finally { if (!disposed && current === importSession) backupBusy.value = false }
}
function openConfirmation() {
  if (!preview.value || !importFile.value || backupBusy.value) return
  consentSession = importSession
  replaceConfirmed.value = false
  confirmVisible.value = true
}
async function importBackup() {
  const current = importSession, backup = importFile.value
  if (!confirmVisible.value || !replaceConfirmed.value || consentSession !== current || !preview.value || !backup || backupBusy.value || saving.value || loading.value) return
  backupBusy.value = true
  importing.value = true
  settingsSequence++
  try {
    await api('/backups/import', 'POST', { backup, replace: true })
    if (disposed || current !== importSession) return
    clearBackup()
    importSuccess.value = true
    form.value = null
    saved.value = null
    await load()
    if (!disposed && current + 1 === importSession) ElMessage.success('配置已恢复，定时保持暂停，请检查后恢复')
  } catch (e) {
    if (!disposed && current === importSession) backupError.value = e.message
  } finally {
    if (!disposed && current === importSession) { backupBusy.value = false; importing.value = false }
  }
}
async function loadSystem() {
  const current = ++systemSequence
  systemLoading.value = true
  systemError.value = ''
  try {
    const status = await api('/system/status')
    if (!disposed && current === systemSequence && tab.value === 'about') system.value = status
  } catch (e) {
    if (!disposed && current === systemSequence) systemError.value = e.message
  } finally { if (!disposed && current === systemSequence) systemLoading.value = false }
}
</script>

<template>
  <div class="settings-page">
    <header class="page-heading settings-heading"><div><h1>设置与备份</h1><p>保存执行偏好，也为自己的配置留一份备份。</p></div></header>
    <div class="settings-tabs" role="tablist" aria-label="设置分类">
      <button v-for="([key, label], index) in categories" :id="`settings-tab-${key}`" :key="key" role="tab"
        :aria-selected="tab === key" :aria-controls="`settings-panel-${key}`" :tabindex="tab === key ? 0 : -1"
        :class="{ active: tab === key }" @click="changeTab(key)" @keydown="tabKeydown($event, index)">{{ label }}</button>
    </div>
    <p v-if="settingsError" class="settings-notice error" role="alert">{{ settingsError }} <el-button size="small" :disabled="saving || loading || importing" @click="reload">重新载入</el-button></p>
    <div :id="`settings-panel-${tab}`" role="tabpanel" :aria-labelledby="`settings-tab-${tab}`">
      <template v-if="tab === 'execution' || tab === 'proxy'">
        <div v-if="loading" class="settings-loading" role="status">正在读取设置…</div>
        <div v-else-if="!form" class="settings-loading">设置尚未读取，无法编辑。<el-button size="small" @click="load">重试读取</el-button></div>
        <div v-else class="settings-grid">
          <section v-if="tab === 'execution'" class="panel settings-panel">
            <div class="settings-panel-heading"><h2>执行偏好</h2><span>配置版本 v{{ saved.version }}</span></div>
            <el-form :disabled="saving || importing" class="settings-form">
              <div class="setting-row"><div><h3>暂停全局定时</h3><p>只暂停新的自动触发，手动执行与已有队列不受影响。</p></div><el-switch v-model="form.paused" aria-label="暂停全局定时" /></div>
              <div class="setting-row"><div><h3>同时执行的平台数</h3><p>跨平台有界并发，同平台内始终串行。</p></div><div class="setting-value"><el-input-number v-model="form.concurrency" aria-label="同时执行的平台数" :min="1" :max="8" :precision="0" /><span>个</span></div></div>
              <div class="setting-row"><div><h3>全局请求超时上限</h3><p>1–120 秒，与 cURL 自身超时取严格值。</p></div><div class="setting-value"><el-input-number v-model="form.timeoutSeconds" aria-label="全局请求超时上限" :min="1" :max="120" :precision="0" /><span>秒</span></div></div>
              <div class="setting-row"><div><h3>执行日志保留</h3><p>1–365 天，响应随执行记录清理；日标记与自动去重账本独立保留。</p></div><div class="setting-value"><el-input-number v-model="form.retentionDays" aria-label="执行日志保留天数" :min="1" :max="365" :precision="0" /><span>天</span></div></div>
            </el-form>
            <p class="settings-panel-footnote">保存后对新开始发送的请求生效，已在执行的请求继续使用原配置；设置重启后保留。</p>
          </section>
          <section v-else class="panel settings-panel">
            <div class="settings-panel-heading"><h2>请求代理</h2><span>仅影响 Java 服务发出的请求</span></div>
            <el-form label-position="top" :disabled="saving || importing" class="proxy-form">
              <el-radio-group v-model="form.proxy.mode" class="proxy-options" aria-label="请求代理模式">
                <el-radio value="system" class="proxy-option" border><span><strong>跟随启动配置</strong><small>采用 Java 启动代理设置</small></span></el-radio>
                <el-radio value="direct" class="proxy-option" border><span><strong>直连</strong><small>不使用应用代理</small></span></el-radio>
                <el-radio value="http" class="proxy-option" border><span><strong>HTTP 代理</strong><small>HTTP／混合代理端口</small></span></el-radio>
                <el-radio value="socks" class="proxy-option" border><span><strong>SOCKS5 代理</strong><small>无需认证的 SOCKS5 代理</small></span></el-radio>
              </el-radio-group>
              <div v-if="['http', 'socks'].includes(form.proxy.mode)" class="proxy-fields">
                <el-form-item label="代理主机"><el-input v-model="form.proxy.host" aria-label="代理主机" placeholder="例如 127.0.0.1，不含协议和端口" :maxlength="253" /></el-form-item>
                <el-form-item label="代理端口"><el-input-number v-model="form.proxy.port" aria-label="代理端口" :min="1" :max="65535" :precision="0" /></el-form-item>
              </div>
              <div class="settings-helper">
                <template v-if="form.proxy.mode === 'system'">跟随 Java 启动配置，不代表自动读取浏览器或操作系统的所有代理设置。</template>
                <template v-else-if="form.proxy.mode === 'direct'">请求按直连方式发出，仍保留目标网络策略与 TLS 证书／主机名校验。</template>
                <template v-else>代理运行在 Java 服务所在电脑；仅支持无需认证的代理，代理不可用时不会回退直连。v2rayN 用户选择 HTTP 代理，填写 127.0.0.1 和实际 HTTP／混合代理端口。</template>
              </div>
              <p class="settings-small-note">保存后对新开始发送的请求生效，正在执行的请求保持原配置；重启后保留。</p>
            </el-form>
          </section>
          <aside v-if="tab === 'execution'" class="setting-advice"><h3>保留确定性，不冒险重试。</h3><p><strong>凭证过期</strong><br>暂停对应请求，等待更新完整 cURL。</p><p><strong>结果待确认</strong><br>超时或断开可能已完成，系统不自动重试不确定请求。先核实，再主动重跑。</p><p><strong>启停约束</strong><br>重新执行也不能绕过平台、账号、请求停用与凭证暂停。</p></aside>
          <aside v-else class="setting-advice"><h3>代理不是浏览器模拟。</h3><p>使用 Hutool HTTP，不承诺复现浏览器指纹或支持所有 cURL 选项。</p><p>不执行粘贴的 cURL shell，也不读取引用的本地文件；不从 cURL 接受代理指令，不关闭 TLS 校验。</p><p>每次请求使用独立 HTTP 对象与 Cookie，不使用全局 Cookie。</p></aside>
        </div>
      </template>
      <template v-else-if="tab === 'backup'">
        <div class="settings-notice warning"><strong>请求与配置备份均为敏感明文。</strong><p>只下载到可信位置，请勿公开分享。备份内容只在当前页面内存中保留，离开此分类后清除。</p></div>
        <p v-if="importSuccess" class="settings-notice" role="status">配置已恢复，定时保持暂停，请检查后恢复。</p>
        <p v-if="backupError" class="settings-notice error" role="alert">{{ backupError }}</p>
        <div class="backup-layout">
          <section class="panel settings-panel"><div class="settings-panel-heading"><h2>导出配置 / 完整备份</h2><span>明文 JSON</span></div><div class="settings-panel-pad">
            <div class="backup-mode"><strong>普通配置</strong><p>平台、账号、名称、规则、模板、计划与设置；默认不含系统保存的 cURL、URL、Headers、Body 和日标记。恢复后请求停用，需更新 cURL。</p></div>
            <el-checkbox v-model="includeRequests" :disabled="backupBusy">包含完整请求（明文，可能含凭证）</el-checkbox>
            <p class="settings-small-note">完整备份保留当前请求及完成／待确认标记；两者均不包含历史修订、执行日志、响应体或活动队列。</p>
            <div v-if="includeRequests" class="settings-helper warning">完整备份含明文 Cookie、Token、签名等敏感信息，请仅保存到可信位置，不要公开分享。</div>
            <p class="settings-small-note">两种模式都可能包含手工填入名称、备注或规则值的敏感信息，不保证隐私安全，导出前请自行检查。</p>
            <el-button type="primary" :loading="backupBusy && !importing" :disabled="backupBusy" @click="exportBackup">导出{{ includeRequests ? '完整备份' : '配置' }}</el-button>
            <p class="settings-tiny-note">明文配置格式 · signdesk-plain-v1</p>
          </div></section>
          <section class="panel settings-panel"><div class="settings-panel-heading"><h2>恢复备份</h2><span>先预览，再整体替换</span></div><div class="settings-panel-pad">
            <label class="backup-file-drop"><span class="backup-file-symbol" aria-hidden="true"><DeskIcon name="arrow" style="transform:rotate(90deg);width:25px;height:25px" /></span><strong>选择明文 JSON 备份文件</strong><span>最多 12 MiB · 仅支持 signdesk-plain-v1</span><input ref="fileInput" type="file" accept="application/json,.json" aria-label="选择备份文件" :disabled="backupBusy" @change="chooseFile" /></label>
            <p v-if="fileName" class="backup-file-name">{{ fileName }}</p>
            <p class="settings-small-note">不支持旧格式。先由服务端校验预览，再明确确认替换全部配置；导入后定时保持暂停，不触发请求。</p>
            <div class="backup-actions"><el-button :disabled="!importFile || backupBusy" :loading="backupBusy && !importing" @click="previewImport">预览导入</el-button><el-button v-if="importFile || fileName" :disabled="backupBusy" @click="clearBackup">清除文件</el-button></div>
            <div v-if="preview" class="import-preview"><h3>校验通过 · 还未导入</h3><div class="inline-info import-counts">{{ preview.platforms }} 个平台、{{ preview.accounts }} 个账号、{{ preview.requests }} 个请求，{{ preview.templates || 0 }} 份接口模板。{{ preview.includesRequests ? '包含完整请求' : '不含请求内容，恢复后需更新 cURL' }}。{{ preview.message }}</div><p class="settings-small-note">导入不是合并；不会恢复历史、响应或活动队列。导入后全局定时保持暂停。</p><el-button type="warning" :disabled="backupBusy" @click="openConfirmation">确认替换并恢复</el-button></div>
            <p class="settings-small-note">需要队列空闲，服务端持有维护互斥并在短事务中一致替换；校验或导入失败会回滚，不会触发请求。建议先导出现有配置。</p>
          </div></section>
        </div>
      </template>
      <template v-else>
        <div class="system-grid"><section class="panel settings-panel"><div class="settings-panel-pad"><div class="system-mark"><span aria-hidden="true"><DeskIcon name="check" /></span><div><h2>SignDesk</h2><p>签行 · 个人请求与签到管理</p></div></div>
          <p v-if="systemError" class="settings-notice error" role="alert">{{ systemError }}</p>
          <dl class="system-details"><dt>版本</dt><dd>{{ system?.version || '—' }}</dd><dt>服务状态</dt><dd>{{ system ? (system.ready ? '就绪' : '尚未就绪') : '未读取' }}</dd><dt>全局定时</dt><dd>{{ system ? (system.paused ? '已暂停' : '已启用') : '—' }}</dd><dt>活动批次</dt><dd>{{ system?.activeBatches ?? '—' }} 个</dd><dt>服务器时间</dt><dd>{{ dateTime(system?.now) }}（北京）</dd><dt>持久化方式</dt><dd>SQLite · 数据目录位于程序包外</dd></dl>
          <el-button size="small" :loading="systemLoading" @click="loadSystem">刷新系统状态</el-button>
        </div></section><section class="panel settings-panel"><div class="settings-panel-heading"><h2>访问与数据边界</h2></div><div class="settings-panel-pad"><p class="settings-small-note">正式系统默认监听本机，没有应用登录。远程访问请使用自己的私有入口；一个数据目录只能由一个服务进程使用。</p><div class="settings-helper warning">请求、执行响应和配置备份均为明文。请通过操作系统权限、磁盘与备份管理、私有网络保护访问。</div><p class="settings-small-note">配置保存在服务器，运行数据独立于程序包。前端页面关闭不影响服务器任务；停止 Spring Boot 服务后，任务也会停止。</p><p class="settings-small-note">响应正文最多 1 MiB，列表不含正文；详情按需读取且禁止缓存，HTML 仅作为纯文本显示。</p></div></section></div>
      </template>
    </div>
    <div v-if="form && (tab === 'execution' || tab === 'proxy')" class="save-bar"><span role="status">{{ dirty ? '● 有未保存的修改' : '当前配置已保存' }} · v{{ saved.version }}</span><div class="save-bar-actions"><el-button :disabled="!dirty || saving || loading || importing" @click="reload">放弃修改</el-button><el-button type="primary" :loading="saving" :disabled="!dirty || loading || importing || conflict" @click="save">保存设置</el-button></div></div>
    <el-dialog v-model="confirmVisible" class="settings-import-dialog" title="确认整体替换配置" width="min(520px, calc(100vw - 32px))" destroy-on-close :close-on-click-modal="false" :close-on-press-escape="!importing" :show-close="!importing">
      <div class="settings-helper warning">将替换全部配置，不是合并导入。平台、账号、请求、规则模板、计划和设置都将整体替换；历史、响应与活动队列不恢复。建议先导出现有配置。</div>
      <p class="settings-small-note">导入成功后全局定时保持暂停，不触发请求。普通配置中的请求停用，需更新 cURL。未保存的执行偏好与代理草稿也将被替换。</p>
      <p v-if="backupError" class="settings-notice error" role="alert">{{ backupError }}</p>
      <el-checkbox v-model="replaceConfirmed" :disabled="importing" class="replace-checkbox">我理解这是整体替换，而不是合并</el-checkbox>
      <template #footer><el-button :disabled="importing" @click="confirmVisible = false">取消</el-button><el-button type="warning" :disabled="!replaceConfirmed || saving || loading" :loading="importing" @click="importBackup">确认整体替换</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.settings-page { color: #173b31; font: 13px/1.6 "Microsoft YaHei", "微软雅黑", sans-serif; min-width: 0; --el-color-primary: #214e3e; --el-color-primary-light-9: #f0f5e9; --el-color-primary-light-3: #7f9b79; --el-border-radius-base: 7px; --el-color-warning: #96713c; --el-color-warning-light-9: #faf4e7; }
.settings-heading { margin-bottom: 23px; }
.settings-heading h1 { margin: 0; font-size: 23px; font-weight: 600; line-height: 1.65; }
.settings-heading p { font-size: 11px; color: var(--muted); margin: 6px 0 0; }
.settings-tabs { display: flex; gap: 27px; border-bottom: 1px solid #e3e8dc; margin-bottom: 23px; overflow-x: auto; }
.settings-tabs button { padding: 0 0 13px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: #7c896f; font: inherit; font-size: 12px; white-space: nowrap; cursor: pointer; }
.settings-tabs button.active { color: #214e3e; border-color: #214e3e; }
.settings-tabs button:focus-visible { outline: 2px solid #214e3e; outline-offset: -2px; }
.settings-grid { display: grid; grid-template-columns: minmax(0, 1fr) 250px; gap: 22px; align-items: start; }
.settings-panel { padding: 0; margin: 0; background: #fff; border: 1px solid #e3e8dc; border-radius: 12px; overflow: hidden; min-width: 0; }
.settings-panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 20px 23px; }
.settings-panel-heading h2 { margin: 0; font-size: 14px; font-weight: 600; }
.settings-panel-heading > span { font-size: 10px; color: #8b987c; }
.setting-row { display: flex; gap: 22px; justify-content: space-between; align-items: center; padding: 21px 23px; border-top: 1px solid #edf0e8; }
.setting-row > div:first-child { min-width: 0; }
.setting-row h3 { margin: 0; font-size: 12px; font-weight: 500; color: #214e3e; }
.setting-row p { font-size: 11px; color: #7c8a73; margin: 6px 0 0; }
.setting-value { display: flex; align-items: center; gap: 8px; flex-shrink: 0; color: #8b987c; font-size: 11px; }
.setting-value :deep(.el-input-number) { width: 124px; }
.settings-panel-footnote { border-top: 1px solid #edf0e8; padding: 16px 23px; font-size: 11px; line-height: 1.9; color: #7c8a73; margin: 0; }
.setting-advice { background: #eef2e7; padding: 21px; border-radius: 12px; border: 1px solid #e2e8d9; }
.setting-advice h3 { font-size: 12px; font-weight: 600; margin: 0 0 14px; }
.setting-advice p { font-size: 11px; color: #77876a; margin: 13px 0 0; line-height: 1.9; }
.setting-advice strong { color: #596e4c; font-weight: 500; }
.proxy-form { padding: 0 23px 19px; }
.proxy-form :deep(.el-form-item__label) { font-size: 12px; color: #456044; padding-bottom: 7px; }
.proxy-form :deep(.el-form-item) { margin-bottom: 20px; }
.proxy-options { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 11px; margin-bottom: 22px; }
.proxy-option { margin: 0; padding: 16px 13px; height: auto; min-height: 76px; border-color: #e3e8dc; border-radius: 8px; align-items: flex-start; }
.proxy-option.is-checked { border-color: #adc594; background: #f2f7eb; }
.proxy-option :deep(.el-radio__input) { margin-top: 3px; }
.proxy-option :deep(.el-radio__label) { white-space: normal; padding-left: 9px; min-width: 0; }
.proxy-option strong { display: block; font-size: 12px; font-weight: 500; color: #214e3e; }
.proxy-option small { display: block; font-size: 10px; color: #809071; margin-top: 5px; line-height: 1.7; }
.proxy-fields { display: grid; grid-template-columns: minmax(0, 1fr) 160px; gap: 17px; }
.proxy-fields :deep(.el-input-number) { width: 100%; }
.settings-helper { padding: 13px 15px; border: 1px solid #e3e9d9; border-radius: 8px; background: #f5f7ef; color: #758569; font-size: 11px; line-height: 1.9; }
.settings-helper.warning, .settings-notice.warning { border-color: #e9dbbd; background: #fbf7eb; color: #92703d; }
.settings-small-note { font-size: 11px; color: #7c8a73; margin: 14px 0; line-height: 1.9; overflow-wrap: anywhere; }
.settings-tiny-note { font-size: 10px; color: #8b987c; margin: 12px 0 0; }
.save-bar { position: sticky; bottom: 18px; margin-top: 17px; border: 1px solid #d7e1cc; border-radius: 9px; padding: 13px 17px; background: #f0f5e9; display: flex; align-items: center; justify-content: space-between; gap: 12px; box-shadow: 0 4px 20px #21321d08; z-index: 4; }
.save-bar > span { font-size: 11px; color: #6a7a5b; }
.save-bar-actions { display: flex; align-items: center; gap: 9px; }
.save-bar-actions :deep(.el-button) { margin-left: 0; }
.backup-layout, .system-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 22px; align-items: start; }
.settings-panel-pad { padding: 0 23px 22px; }
.settings-panel-pad .el-checkbox { max-width: 100%; white-space: normal; height: auto; min-height: 32px; }
.settings-panel-pad :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; font-size: 12px; }
.backup-mode { border: 1px solid #e3e8dc; border-radius: 8px; padding: 14px; background: #fafbf7; margin-bottom: 13px; }
.backup-mode strong { font-size: 12px; font-weight: 500; }
.backup-mode p { font-size: 11px; color: #7c8a73; margin: 6px 0 0; line-height: 1.9; }
.backup-file-drop { display: flex; flex-direction: column; gap: 8px; align-items: center; background: #fafbf7; border: 1px dashed #cbd6bb; border-radius: 8px; padding: 22px 13px; text-align: center; font-size: 11px; color: #7c8a73; }
.backup-file-drop strong { font-weight: 500; font-size: 12px; color: #506c43; }
.backup-file-symbol { color: #7f9b69; font-size: 26px; line-height: 1; }
.backup-file-drop input { max-width: 100%; width: 100%; font: inherit; margin-top: 4px; color: #7c8a73; }
.backup-file-drop input::file-selector-button { border: 1px solid #d8e1cc; border-radius: 6px; background: #fff; color: #214e3e; padding: 6px 10px; margin-right: 8px; font: inherit; cursor: pointer; }
.backup-file-name { overflow-wrap: anywhere; font-size: 11px; color: #526e43; margin: 10px 0 0; }
.backup-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.backup-actions :deep(.el-button) { margin-left: 0; }
.import-preview { background: #f2f6eb; border: 1px solid #dce5cf; border-radius: 8px; padding: 16px; margin-top: 18px; }
.import-preview h3 { font-size: 12px; margin: 0 0 10px; font-weight: 600; color: #506c43; }
.import-counts { background: transparent; border: 0; padding: 0; margin: 0; font-size: 11px; color: #6b7c59; }
.settings-notice { background: #edf2e4; color: #677c50; border: 1px solid #dce5cf; border-radius: 9px; padding: 13px 17px; margin: 0 0 20px; font-size: 12px; overflow-wrap: anywhere; }
.settings-notice > strong { font-size: 12px; font-weight: 500; }
.settings-notice > p { font-size: 11px; margin: 4px 0 0; }
.settings-notice.error { color: #a44e42; background: #faf0e9; border-color: #eedbcb; }
.settings-loading { border: 1px solid #e3e8dc; border-radius: 12px; padding: 35px 23px; background: #fff; color: #7c8a73; }
.system-mark { display: flex; gap: 13px; align-items: center; padding-top: 22px; margin-bottom: 24px; }
.system-mark > span { width: 39px; height: 39px; border-radius: 10px; display: grid; place-items: center; background: #214e3e; color: #dcebb5; font-size: 23px; }
.system-mark h2 { margin: 0; font-size: 17px; font-weight: 600; }
.system-mark p { margin: 4px 0 0; font-size: 11px; color: #7c8a73; }
.system-details { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 13px; font-size: 11px; margin: 0 0 25px; }
.system-details dt { color: #8b987c; }
.system-details dd { margin: 0; color: #526c43; overflow-wrap: anywhere; }
.settings-import-dialog { --el-dialog-border-radius: 12px; --el-color-primary: #214e3e; --el-color-warning: #96713c; font: 13px/1.6 "Microsoft YaHei", "微软雅黑", sans-serif; }
.settings-import-dialog :deep(.el-dialog__title) { color: #173b31; font-size: 18px; }
.replace-checkbox { max-width: 100%; white-space: normal; height: auto; margin-top: 5px; }
.replace-checkbox :deep(.el-checkbox__label) { white-space: normal; line-height: 1.8; font-size: 12px; }
@media (max-width: 730px) { .settings-heading h1 { font-size: 19px; } .settings-heading { margin-bottom: 19px; } .settings-heading p { font-size: 10px; } }
@media (max-width: 1100px) { .settings-grid { grid-template-columns: minmax(0, 1fr) 220px; gap: 17px; } .setting-row { padding: 20px; gap: 14px; } .backup-layout { gap: 17px; } }
@media (max-width: 900px) { .settings-grid, .backup-layout, .system-grid { grid-template-columns: 1fr; gap: 17px; } .setting-advice { padding: 18px; } }
@media (max-width: 620px) { .settings-heading p { font-size: 10px; } .settings-tabs { gap: 22px; margin-bottom: 18px; } .settings-tabs button { font-size: 11px; padding-bottom: 11px; } .settings-panel-heading { padding: 18px 17px; } .settings-panel-heading > span { font-size: 9px; } .setting-row { padding: 18px 17px; gap: 13px; align-items: flex-start; } .setting-row h3 { font-size: 11px; } .setting-row p { font-size: 10px; } .setting-value { gap: 5px; } .setting-value :deep(.el-input-number) { width: 112px; } .settings-panel-pad { padding: 0 17px 19px; } .settings-panel-footnote { padding: 14px 17px; } .proxy-form { padding: 0 17px 18px; } .proxy-fields { grid-template-columns: 1fr; gap: 0; } .save-bar { bottom: 78px; flex-wrap: wrap; padding: 11px 13px; } .save-bar-actions { margin-left: auto; } .save-bar-actions :deep(.el-button) { font-size: 11px; padding: 8px 10px; } .system-details { grid-template-columns: 88px minmax(0, 1fr); gap: 12px; } }
</style>
