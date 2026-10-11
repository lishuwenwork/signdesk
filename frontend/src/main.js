import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import AppShell from './components/AppShell.vue'
import Login from './views/Login.vue'
import Dashboard from './views/Dashboard.vue'
import Platforms from './views/Platforms.vue'
import Schedules from './views/Schedules.vue'
import Runs from './views/Runs.vue'
import Settings from './views/Settings.vue'
import { auth, clearSession, ensureSession, sessionVersion } from './auth'
import { configureSession } from './api'
import { safeReturnPath } from './authState.js'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: Login },
    { path: '/', component: AppShell, children: [
      { path: '', component: Dashboard },
      { path: 'platforms', component: Platforms },
      { path: 'schedules', component: Schedules },
      { path: 'runs', component: Runs },
      { path: 'settings', component: Settings },
    ] },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(async (to) => {
  try { await ensureSession() }
  catch {
    // The login page provides the connection error and a retry, never an unprotected shell.
    if (to.name !== 'login') return { name: 'login', query: { redirect: safeReturnPath(to.fullPath) } }
    return true
  }
  if (to.name === 'login') return auth.username ? safeReturnPath(to.query.redirect) : true
  if (!auth.username) return { name: 'login', query: { redirect: safeReturnPath(to.fullPath) } }
})
configureSession({
  getSessionVersion: sessionVersion,
  onUnauthorized: () => {
    const redirect = safeReturnPath(router.currentRoute.value.fullPath)
    clearSession()
    if (router.currentRoute.value.name !== 'login') router.replace({ name: 'login', query: { redirect, expired: '1' } })
  },
})
createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
