<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, report, confirm } from '../api'
import { readBackupFile } from '../backupFile.mjs'
const form = reactive({ paused: false, concurrency: 2, timeoutSeconds: 20, retentionDays: 30, version: 1,
  proxy: { mode: 'system', host: '', port: 0 } })
const saving = ref(false),
  includeRequests = ref(false)
/** @type {import('vue').Ref<import('../backupFile.mjs').BackupFile | null>} */
const importFile = ref(null)
/** @type {import('vue').Ref<{platforms: number, accounts: number, requests: number, templates: number, includesRequests: boolean, message: string} | null>} */
const preview = ref(null)
/** @type {import('vue').Ref<HTMLInputElement | null>} */
const fileInput = ref(null)
const busy = ref(false)
let importSession = 0
onUnmounted(() => {
  importSession++
  importFile.value = null
  preview.value = null
})
async function load() {
  try {
    Object.assign(form, await api('/settings'))
  } catch (e) {
    report(e)
  }
}
onMounted(load)
async function save() {
  saving.value = true
  try {
    await api('/settings', 'PUT', form)
    await load()
    ElMessage.success('设置已保存')
  } catch (e) {
    report(e)
  } finally {
    saving.value = false
  }
}
async function exportBackup() {
  busy.value = true
  try {
    const data = await api('/backups/export', 'POST', {
      includeRequests: includeRequests.value,
    })
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `signdesk-${includeRequests.value ? 'full-backup' : 'config'}-${Date.now()}.json`
    link.click()
    URL.revokeObjectURL(link.href)
    ElMessage.success('明文 JSON 已导出，请妥善保管')
  } catch (e) {
    report(e)
  } finally {
    busy.value = false
  }
}
/** @param {Event} event @returns {Promise<void>} */
async function chooseFile(event) {
  const current = ++importSession
  preview.value = null
  importFile.value = null
  const file = event.target.files?.[0]
  if (!file) return
  busy.value = true
  try {
    const backup = await readBackupFile(file)
    if (current === importSession) importFile.value = backup
  } catch (e) {
    if (current === importSession) report(e)
  } finally {
    if (current === importSession) busy.value = false
  }
}
async function previewImport() {
  if (!importFile.value) return
  const current = importSession
  preview.value = null
  busy.value = true
  try {
    const result = await api('/backups/preview', 'POST', { backup: importFile.value })
    if (current === importSession) preview.value = result
  } catch (e) {
    if (current === importSession) report(e)
  } finally {
    if (current === importSession) busy.value = false
  }
}
async function importBackup() {
  const current = importSession, backup = importFile.value
  if (!preview.value || !backup) return
  if (!(await confirm('将替换全部配置，不是合并导入。' + preview.value.message + ' 建议先导出现有配置；下载文件均为明文，请妥善保管。'))) return
  if (current !== importSession) return
  busy.value = true
  try {
    await api('/backups/import', 'POST', { backup, replace: true })
    if (current !== importSession) return
    importFile.value = null
    preview.value = null
    if (fileInput.value) fileInput.value.value = ''
    await load()
    ElMessage.success('配置已恢复，定时保持暂停，请检查后恢复')
  } catch (e) {
    if (current === importSession) report(e)
  } finally {
    if (current === importSession) busy.value = false
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>设置与备份</h1>
      <div class="muted">配置保存在服务器，运行数据独立于程序包。</div>
    </div>
  </div>
  <div class="setting-layout">
    <section class="panel">
      <div class="panel-head"><h2>执行偏好</h2></div>
      <el-form label-position="top" class="form-narrow"
        ><el-form-item label="暂停全局定时"
          ><el-switch v-model="form.paused" />
          <div class="muted" style="margin-left: 12px">手动执行和现有队列继续可用</div></el-form-item
        ><el-form-item label="同时执行的平台数"
          ><el-input-number v-model="form.concurrency" :min="1" :max="8" />
          <p class="muted">同平台内始终串行，避免账号间抢占。</p></el-form-item
        ><el-form-item label="全局请求超时上限（秒）"
          ><el-input-number v-model="form.timeoutSeconds" :min="1" :max="120" /></el-form-item
        ><el-form-item label="日志保留（天）"
          ><el-input-number v-model="form.retentionDays" :min="1" :max="365" /></el-form-item
        ><el-form-item label="请求代理"
          ><el-select v-model="form.proxy.mode" aria-label="请求代理模式">
            <el-option label="跟随 Java 启动配置" value="system" />
            <el-option label="直连" value="direct" />
            <el-option label="HTTP 代理" value="http" />
            <el-option label="SOCKS5 代理" value="socks" />
          </el-select></el-form-item
        ><template v-if="['http', 'socks'].includes(form.proxy.mode)">
          <el-form-item label="代理主机"><el-input v-model="form.proxy.host" aria-label="代理主机" placeholder="例如 127.0.0.1，不含协议和端口" /></el-form-item>
          <el-form-item label="代理端口"><el-input-number v-model="form.proxy.port" aria-label="代理端口" :min="1" :max="65535" /></el-form-item>
        </template>
        <p class="muted">v2rayN 用户选择 HTTP 代理，填写 127.0.0.1 和实际 HTTP／混合代理端口。代理运行在 Java 服务所在电脑；当前支持无需认证的代理。</p>
        <p class="muted">保存后对新开始发送的请求生效，重启后保留。已在执行的请求继续使用原配置。</p>
      </el-form
      ><el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
      <div class="inline-info">
        超时或断开会产生待确认状态，系统不自动重试不确定请求。凭证过期需更新完整 cURL。
      </div>
    </section>
    <div>
      <section class="panel">
        <div class="panel-head"><h2>导出配置 / 完整备份</h2></div>
        <el-checkbox v-model="includeRequests" :disabled="busy">包含完整请求（明文，可能含凭证）</el-checkbox>
        <p class="muted">
          两种导出均为明文 JSON。默认不含系统保存的 cURL、URL、Headers 和 Body，恢复后请求停用，需更新 cURL。
          完整备份保留当前请求及完成／待确认标记；两者均不包含历史修订、执行日志、响应体或活动队列。
        </p>
        <el-alert v-if="includeRequests" class="space-top" type="warning" :closable="false"
          title="完整备份含明文 Cookie、Token、签名等敏感信息，请仅保存到可信位置，不要公开分享。" />
        <p class="muted">两种模式都可能包含手工填入名称、备注或规则值的敏感信息，不保证隐私安全，导出前请自行检查。</p>
        <el-button :loading="busy" @click="exportBackup"
          >导出{{ includeRequests ? '完整备份' : '配置' }}</el-button
        >
      </section>
      <section class="panel">
        <div class="panel-head"><h2>恢复备份</h2></div>
        <input
          ref="fileInput"
          type="file"
          accept="application/json,.json"
          aria-label="选择备份文件"
          :disabled="busy"
          @change="chooseFile"
        />
        <p class="muted">仅支持 signdesk-plain-v1 明文 JSON（最多 12 MiB），不支持旧格式。先预览校验，再确认替换全部配置；导入后定时保持暂停。</p>
        <div class="actions space-top">
          <el-button :disabled="!importFile" :loading="busy" @click="previewImport">预览导入</el-button
          ><el-button v-if="preview" type="warning" :loading="busy" @click="importBackup"
            >确认替换并恢复</el-button
          >
        </div>
        <div v-if="preview" class="inline-info">
          {{ preview.platforms }} 个平台、{{ preview.accounts }} 个账号、{{ preview.requests }} 个请求，
          {{ preview.templates || 0 }} 份接口模板。{{
            preview.includesRequests ? '包含完整请求' : '不含请求内容，恢复后需更新 cURL'
          }}。{{ preview.message }}
        </div>
      </section>
    </div>
  </div>
  <div class="inline-info">
    正式系统默认监听本机，没有应用登录。远程访问请使用自己的私有入口。前端页面关闭不影响服务器任务；停止
    Spring Boot 服务后，任务也会停止。
  </div>
</template>
