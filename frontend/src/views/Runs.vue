<script setup>
import { reactive, ref } from 'vue'
import { api, report, periodic, execute, labels, tones, dateTime } from '../api'
const platforms = ref([]),
  data = ref({ items: [], total: 0 }),
  error = ref(''),
  drawer = ref(false),
  detail = ref(null)
const filter = reactive({ platformId: '', status: '', source: '', page: 1 })
async function load() {
  try {
    const query = new URLSearchParams({ ...filter, size: 20 })
    data.value = await api(`/runs?${query}`)
    platforms.value = await api('/platforms')
    error.value = ''
    if (drawer.value && detail.value) detail.value = await api(`/runs/${detail.value.id}`)
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
  try {
    detail.value = await api(`/runs/${row.id}`)
    drawer.value = true
  } catch (e) {
    report(e)
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
    记录只保存状态、HTTP、耗时和固定摘要，不保存完整请求或响应。每日完成与待确认标记不会随日志清理而丢失。
  </div>
  <el-drawer v-model="drawer" title="执行记录详情" size="460px"
    ><template v-if="detail"
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
