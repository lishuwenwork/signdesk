<script setup>
import { computed, provide, readonly, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, periodic } from './api'
const route = useRoute()
const online = ref(false)
const paused = ref(false)
const navCollapsed = ref(false)
const navToggleLabel = computed(() => navCollapsed.value ? '展开菜单导航' : '收起菜单导航')
provide('systemStatus', { online: readonly(online), paused: readonly(paused) })
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
  <div class="app-layout" :class="{ 'nav-collapsed': navCollapsed }">
    <aside class="sidebar">
      <div class="sidebar-header">
        <RouterLink to="/" class="brand" aria-label="签行 SignDesk" title="签行 SignDesk">
          <span class="brand-symbol" aria-hidden="true">✓</span>
          <span class="brand-copy">签行<small>SignDesk</small></span>
        </RouterLink>
        <button type="button" class="nav-toggle" :aria-label="navToggleLabel" :title="navToggleLabel"
          :aria-expanded="!navCollapsed" aria-controls="main-navigation" @click="navCollapsed = !navCollapsed">
          <span class="desktop-nav-control" aria-hidden="true">{{ navCollapsed ? '›' : '‹' }}</span>
          <span class="mobile-nav-control" aria-hidden="true">☰</span>
        </button>
      </div>
      <nav id="main-navigation" aria-label="主导航">
        <RouterLink v-for="menu in menus" :key="menu.path" :to="menu.path" :aria-label="menu.name" :title="menu.name"
          :class="{ selected: route.path === menu.path }">
          <span class="nav-icon" aria-hidden="true">{{ menu.icon }}</span>
          <span class="menu-title">{{ menu.name }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-footer" role="status" :title="`${online ? '服务运行中' : '连接服务中断'} · v0.1.0`">
        <span class="status-dot" :class="{ offline: !online }" aria-hidden="true"></span>
        <span class="service-label">{{ online ? '服务运行中' : '连接服务中断' }}</span><small>v0.1.0</small>
      </div>
    </aside>
    <main>
      <el-alert v-if="!online" title="后端连接暂不可用，保存和执行需要 Spring Boot 服务运行"
        type="warning" :closable="false" class="connection-alert" />
      <div class="page-content">
        <div v-if="paused" class="page-status">
          <el-tag type="warning" size="small">定时已暂停</el-tag>
        </div>
        <RouterView />
      </div>
    </main>
  </div>
</template>
