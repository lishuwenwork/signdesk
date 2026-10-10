<script setup>
import { ref } from 'vue'
import { api, periodic, execute, report, labels, tones, dateTime } from '../api'
const state = ref(null)
const error = ref('')
async function load() {
  try {
    state.value = await api('/dashboard')
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}
periodic(load, 2000)
async function cancel(id) {
  try {
    await api(`/batches/${id}/cancel`, 'POST', {})
    await load()
  } catch (e) {
    report(e)
  }
}
</script>
<template>
  <div class="page-heading">
    <h1>今日概览</h1>
    <el-button type="primary" @click="execute('all')">执行全部平台</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="space-top" />
  <template v-if="state">
    <div class="stats-grid">
      <div class="stat">
        <div class="stat-label">已管理平台</div>
        <div class="stat-value">{{ state.platforms }}</div>
        <div class="stat-note">{{ state.accounts }} 个账号，独立请求</div>
      </div>
      <div class="stat">
        <div class="stat-label">请求总数</div>
        <div class="stat-value">{{ state.requests }}</div>
        <div class="stat-note">每个账号可以拥有多个请求</div>
      </div>
      <div class="stat">
        <div class="stat-label">今日已完成</div>
        <div class="stat-value">{{ state.completed }}</div>
        <div class="stat-note">按各平台业务时区统计</div>
      </div>
      <div class="stat">
        <div class="stat-label">需要处理</div>
        <div class="stat-value" :class="{ 'danger-text': state.needsAttention }">
          {{ state.needsAttention }}
        </div>
        <div class="stat-note">凭证过期或结果待确认的标记</div>
      </div>
    </div>
    <div class="split-grid">
      <section class="panel">
        <div class="panel-head">
          <h2>接下来的计划</h2>
          <RouterLink to="/schedules" class="muted">管理计划 →</RouterLink>
        </div>
        <div v-for="plan in state.schedules" :key="plan.platformId" class="schedule-row">
          <div>
            <h3>{{ plan.name }}</h3>
            <div class="muted small">
              {{ plan.spec.timezone }} · {{ plan.spec.frequency === 'daily' ? '每日' : '指定星期' }}
            </div>
          </div>
          <div style="text-align: right">
            <div class="schedule-time">{{ plan.spec.times.join(' / ') }}</div>
            <div class="muted small">{{ plan.nextAt ? dateTime(plan.nextAt) : '计划未启用' }}</div>
          </div>
        </div>
        <el-empty v-if="!state.schedules.length" description="添加平台后，设置你的第一个计划"
          ><RouterLink to="/platforms"><el-button type="primary">新增平台</el-button></RouterLink></el-empty
        >
      </section>
      <section class="panel">
        <div class="panel-head">
          <h2>执行队列</h2>
          <span class="muted small">{{ state.active.length }} 个批次</span>
        </div>
        <div v-for="batch in state.active" :key="batch.id" class="queue-card">
          <div class="panel-head">
            <strong>{{ batch.name }}</strong
            ><el-button size="small" @click="cancel(batch.id)">取消剩余</el-button>
          </div>
          <el-progress
            :percentage="batch.total ? Math.round((batch.done / batch.total) * 100) : 100"
            :stroke-width="5"
          />
          <div class="muted small space-top">
            {{ batch.status === 'running' ? '执行中' : '等待执行' }} · {{ batch.done }} / {{ batch.total }} ·
            {{ batch.source === 'auto' ? '定时' : '手动' }}
          </div>
        </div>
        <el-empty v-if="!state.active.length" description="当前没有执行中的任务" :image-size="65" />
      </section>
    </div>
    <section class="panel">
      <div class="panel-head">
        <h2>最近执行</h2>
        <RouterLink to="/runs" class="muted">查看全部 →</RouterLink>
      </div>
      <el-table :data="state.recent" empty-text="还没有执行记录"
        ><el-table-column label="平台 / 账号" min-width="170"
          ><template #default="{ row }"
            ><strong>{{ row.platformName }}</strong>
            <div class="muted small">{{ row.alias }}</div></template
          ></el-table-column
        ><el-table-column prop="requestName" label="请求" min-width="130" /><el-table-column
          label="结果"
          width="110"
          ><template #default="{ row }"
            ><el-tag :type="tones[row.status]" size="small">{{ labels[row.status] }}</el-tag></template
          ></el-table-column
        ><el-table-column prop="safeSummary" label="说明" min-width="230" /><el-table-column
          label="时间（北京）"
          width="180"
          ><template #default="{ row }">{{
            dateTime(row.finishedAt || row.createdAt)
          }}</template></el-table-column
        ></el-table
      >
    </section>
    <div class="inline-info">
      关闭页面不会停止服务器任务。凭证过期后更新整份 cURL；结果待确认时先核对平台，避免重复执行。
    </div>
  </template>
</template>
