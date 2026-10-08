import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import Dashboard from './views/Dashboard.vue'
import Platforms from './views/Platforms.vue'
import Schedules from './views/Schedules.vue'
import Runs from './views/Runs.vue'
import Settings from './views/Settings.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: Dashboard },
    { path: '/platforms', component: Platforms },
    { path: '/schedules', component: Schedules },
    { path: '/runs', component: Runs },
    { path: '/settings', component: Settings },
  ],
})
createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
