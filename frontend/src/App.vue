<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, periodic } from './api'
const route = useRoute()
const online = ref(false)
const paused = ref(false)
const menus = [
  { path: '/', name: '今日概览', icon: '◫' },
  { path: '/platforms', name: '平台与账号', icon: '▤' },
  { path: '/schedules', name: '定时计划', icon: '◷' },
  { path: '/runs', name: '执行记录', icon: '≡' },
  { path: '/settings', name: '设置与备份', icon: '⚙' },
]
periodic(async () => {
  try {
    const state = await api('/system/status')
    online.value = state.ready
    paused.value = state.paused
  } catch {
    online.value = false
  }
})
</script>
<template>
  <div class="app-layout">
    <aside class="sidebar">
      <RouterLink to="/" class="brand"
        ><span class="brand-symbol">✓</span><span>签行<small>SignDesk</small></span></RouterLink
      >
      <div class="nav-label">工作空间</div>
      <nav aria-label="主导航">
        <RouterLink
          v-for="menu in menus"
          :key="menu.path"
          :to="menu.path"
          :class="{ selected: route.path === menu.path }"
          ><span class="nav-icon" aria-hidden="true">{{ menu.icon }}</span
          >{{ menu.name }}</RouterLink
        >
      </nav>
      <div class="sidebar-footer">
        <span class="status-dot" :class="{ offline: !online }"></span
        >{{ online ? '服务运行中' : '连接服务中断' }}<small>个人任务工作台 · v0.1.0</small>
      </div>
    </aside>
    <main>
      <header class="topbar">
        <span
          >我的工作空间 <span class="divider">/</span>
          {{ menus.find((m) => m.path === route.path)?.name }}</span
        >
        <div class="topbar-info">
          <el-tag v-if="paused" type="warning" size="small">定时已暂停</el-tag
          ><span class="private-pill">个人 Web 工具</span>
        </div>
      </header>
      <el-alert
        v-if="!online"
        title="后端连接暂不可用，保存和执行需要 Spring Boot 服务运行"
        type="warning"
        :closable="false"
        class="connection-alert"
      />
      <div class="page-content"><RouterView /></div>
    </main>
  </div>
</template>
