<script setup>
import { computed, provide, readonly, ref, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { api, periodic } from './api'
import DeskIcon from './components/DeskIcon.vue'
const route = useRoute()
const online = ref(false), paused = ref(false), checking = ref(true)
let disposed = false, sequence = 0
provide('systemStatus', { online: readonly(online), paused: readonly(paused) })
const menus = [
  { path: '/', name: '今日概览', icon: 'grid' },
  { path: '/platforms', name: '平台与账号', icon: 'layers' },
  { path: '/schedules', name: '定时计划', icon: 'clock' },
  { path: '/runs', name: '执行记录', icon: 'record' },
  { path: '/settings', name: '设置与备份', icon: 'settings' },
]
const isPlatform = computed(() => route.path === '/platforms')
periodic(async () => {
  const current = ++sequence
  try {
    const state = await api('/system/status')
    if (disposed || current !== sequence) return
    online.value = state.ready
    paused.value = state.paused
  } catch {
    if (!disposed && current === sequence) online.value = false
  } finally {
    if (!disposed && current === sequence) checking.value = false
  }
}, 3000)
onUnmounted(() => { disposed = true; sequence++ })
</script>
<template>
  <div class="app-layout" :class="{ 'platform-view': isPlatform }">
    <aside class="sidebar">
      <RouterLink to="/" class="brand" aria-label="签行 SignDesk" title="签行 SignDesk">
        <span class="brand-symbol"><DeskIcon name="check" /></span><span class="brand-copy">签行</span>
      </RouterLink>
      <nav id="main-navigation" aria-label="主导航">
        <RouterLink v-for="menu in menus" :key="menu.path" :to="menu.path" :aria-label="menu.name" :title="menu.name"
          :aria-current="route.path === menu.path ? 'page' : undefined" :class="{ selected: route.path === menu.path }">
          <DeskIcon :name="menu.icon" /><span class="menu-title">{{ menu.name }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-footer" role="status" :title="checking ? '正在连接服务' : online ? '服务运行中' : '服务连接中断'">
        <span class="status-dot" :class="{ offline: !online }" aria-hidden="true"></span>
        <span class="service-label">{{ checking ? '连接中' : online ? '服务在线' : '连接中断' }}</span><small>SignDesk</small>
      </div>
    </aside>
    <main class="app-main">
      <el-alert v-if="!online && !checking" title="后端连接暂不可用，保存和执行需要服务运行" type="warning" :closable="false" class="connection-alert" />
      <div class="page-content">
        <div v-if="paused" class="page-status"><DeskIcon name="pause" />定时已暂停 · 手动执行和活动队列不受影响</div>
        <RouterView />
      </div>
    </main>
  </div>
</template>
