<script setup>
import { auth } from './auth'
</script>
<template>
  <RouterView v-slot="{ Component, route }">
    <component :is="Component" v-if="route.name === 'login' || auth.username" />
    <div v-else class="session-loading" role="status">正在检查登录状态…</div>
  </RouterView>
</template>
