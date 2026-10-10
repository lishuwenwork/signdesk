<script setup>
import { computed } from 'vue'
import { labels } from '../api'
import DeskIcon from './DeskIcon.vue'
const props = defineProps({ status: String })
const icons = { queued: 'clock', running: 'refresh', success: 'check', already_done: 'check', expired: 'key', failed: 'close', unknown: 'alert', cancelled: 'pause', skipped: 'arrow' }
const text = computed(() => labels[props.status] || props.status || '—')
</script>
<template><span class="status-chip" :class="status"><DeskIcon :name="icons[status] || 'info'" />{{ text }}</span></template>
