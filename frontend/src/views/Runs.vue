<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api, report, periodic, execute, labels, tones, dateTime } from '../api'
const platforms = ref([]),
  data = ref({ items: [], total: 0 }),
  error = ref(''),
  drawer = ref(false),
  detail = ref(null),
  detailLoading = ref(false)
let detailSession = 0
watch(drawer, (open) => {
  if (!open) {
    detailSession++
    detail.value = null
    detailLoading.value = false
  }
})
onUnmounted(() => {
  detailSession++
  detail.value = null
})
const responseMessage = computed(() => {
  if (['queued', 'running'].includes(detail.value?.status)) return '请求尚未完成，等待获取响应体。'
  if (['skipped', 'cancelled'].includes(detail.value?.status)) return '本次没有发送请求，无响应体。'
  return {
    not_recorded: '这条记录没有保存响应体。',
    unavailable: '本次请求未获取到响应体，例如在连接或发送阶段失败、超时。',
  }[detail.value?.response?.state] || '没有可查看的响应体。'
})
const filter = reactive({ platformId: '', status: '', source: '', page: 1 })
async function load() {
  try {
    const query = new URLSearchParams({ ...filter, size: 20 })
    data.value = await api(`/runs?${query}`)
    platforms.value = await api('/platforms')
    error.value = ''
    if (drawer.value && detail.value && ['queued', 'running'].includes(detail.value.status)) {
      const id = detail.value.id, current = detailSession
      const refreshed = await api(`/runs/${id}`)
      if (drawer.value && current === detailSession && detail.value?.id === id) detail.value = refreshed
    }
  } catch (e) {
    error.value = e.message
  }
}
periodic(load, 3000)
function changeFilter() {
  filter.page = 1
  load()
}
async function show(row) {
  const current = ++detailSession
  detail.value = null
  detailLoading.value = true
  drawer.value = true
  try {
    const result = await api(`/runs/${row.id}`)
    if (drawer.value && current === detailSession) detail.value = result
  } catch (e) {
    if (drawer.value && current === detailSession) {
      report(e)
      drawer.value = false
    }
  } finally {
    if (current === detailSession) detailLoading.value = false
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>每一次执行，都有记录。</h1>
      <div class="muted">业务结果单独判断，不将 HTTP 200 直接视为成功。</div>
    </div>
    <el-button @click="load">刷新记录</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-bottom: 20px" />
  <section class="panel">
    <div class="filters">
      <el-select v-model="filter.platformId" placeholder="全部平台" clearable @change="changeFilter"
        ><el-option v-for="p in platforms" :key="p.id" :label="p.name" :value="p.id" /></el-select
      ><el-select v-model="filter.status" placeholder="全部结果" clearable @change="changeFilter"
        ><el-option v-for="(name, key) in labels" :key="key" :label="name" :value="key" /></el-select
      ><el-select v-model="filter.source" placeholder="全部来源" clearable @change="changeFilter"
        ><el-option label="手动执行" value="manual" /><el-option label="定时执行" value="auto"
      /></el-select>
    </div>
    <el-table :data="data.items" empty-text="暂无符合条件的执行记录"
      ><el-table-column label="平台 / 账号" min-width="140"
        ><template #default="{ row }"
          ><strong>{{ row.platformName }}</strong>
          <div class="muted small">{{ row.alias }}</div></template
        ></el-table-column
      ><el-table-column label="请求" min-width="130"
        ><template #default="{ row }"
          >{{ row.requestName }}
          <div class="muted small">
            版本 {{ row.requestRevision }} · {{ row.source === 'auto' ? '定时' : '手动' }}
          </div></template
        ></el-table-column
      ><el-table-column label="结果" width="108"
        ><template #default="{ row }"
          ><el-tag :type="tones[row.status]" size="small">{{ labels[row.status] }}</el-tag></template
        ></el-table-column
      ><el-table-column label="HTTP / 耗时" width="110"
        ><template #default="{ row }"
          >{{ row.httpStatus || '—' }}
          <div class="muted small">{{ row.durationMs == null ? '—' : `${row.durationMs} ms` }}</div></template
        ></el-table-column
      ><el-table-column prop="safeSummary" label="安全摘要" min-width="180" /><el-table-column
        label="时间（北京）"
        width="173"
        ><template #default="{ row }">{{
          dateTime(row.finishedAt || row.createdAt)
        }}</template></el-table-column
      ><el-table-column label="操作" width="88"
        ><template #default="{ row }"
          ><el-button size="small" @click="show(row)">详情</el-button></template
        ></el-table-column
      ></el-table
    >
    <div class="pagination">
      <el-pagination
        v-model:current-page="filter.page"
        :page-size="20"
        :total="data.total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>
  </section>
  <div class="inline-info">
    响应体明文保存，仅在详情中查看，随执行记录按保留天数清理。每日完成与待确认标记不会随记录清理而丢失。
  </div>
  <el-drawer v-model="drawer" title="执行记录详情" size="min(760px, 100vw)" destroy-on-close
    ><div v-if="detailLoading" v-loading="true" style="min-height: 120px" aria-label="加载执行详情" />
    <template v-if="detail"
      ><el-tag :type="tones[detail.status]">{{ labels[detail.status] }}</el-tag>
      <dl class="preview-grid">
        <dt>来源</dt>
        <dd>{{ detail.source === 'auto' ? '平台定时' : '手动执行' }}</dd>
        <dt>业务日期</dt>
        <dd>{{ detail.businessDate }}</dd>
        <dt>请求版本</dt>
        <dd>{{ detail.requestRevision }}</dd>
        <dt>HTTP</dt>
        <dd>{{ detail.httpStatus || '—' }}</dd>
        <dt>耗时</dt>
        <dd>{{ detail.durationMs == null ? '—' : `${detail.durationMs} ms` }}</dd>
        <dt>开始</dt>
        <dd>{{ dateTime(detail.startedAt) }}</dd>
        <dt>结束</dt>
        <dd>{{ dateTime(detail.finishedAt) }}</dd>
        <dt>摘要</dt>
        <dd>{{ detail.safeSummary || '等待执行' }}</dd>
      </dl>
      <h3>响应体</h3>
      <p class="muted small">响应可能包含个人信息或凭证，在服务器明文保存；仅按需展示，关闭详情后清除页面内容。</p>
      <template v-if="detail.response?.body != null">
        <div class="muted small">
          {{ detail.response.contentType || '未提供 Content-Type' }} · {{ detail.response.byteLength }} 字节
          <template v-if="detail.response.encoding === 'text'"> · {{ detail.response.charset }}</template>
        </div>
        <el-alert v-if="detail.response.state === 'truncated'" class="space-top" type="warning" :closable="false"
          title="响应超过 1 MiB，仅保存前 1 MiB；以下内容已截断，执行结果仍为待确认。" />
        <el-alert v-else-if="detail.response.state === 'partial'" class="space-top" type="warning" :closable="false"
          title="响应读取中断，以下仅是实际已收到的内容，并非完整响应。" />
        <el-alert v-if="detail.response.encoding === 'base64'" class="space-top" type="info" :closable="false"
          title="二进制内容或无法按字符集解码，以下以 Base64 原样展示。" />
        <el-empty v-if="detail.response.byteLength === 0" description="响应体为空（0 字节）" :image-size="50" />
        <pre v-else class="preview-code response-body" data-testid="response-body">{{ detail.response.body }}</pre>
      </template>
      <el-alert v-else :title="responseMessage" class="space-top" :closable="false" type="info" />
      <el-alert
        v-if="detail.status === 'unknown'"
        title="可能已在目标平台完成操作，请先核对结果，再决定是否重新执行"
        type="warning"
        :closable="false"
      /><el-button
        type="primary"
        class="space-top"
        :disabled="['queued', 'running', 'expired'].includes(detail.status)"
        @click="execute('request', detail.requestId, true)"
        >重新执行</el-button
      ></template
    ></el-drawer
  >
</template>
<style scoped>
.response-body {
  max-height: 50vh;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
