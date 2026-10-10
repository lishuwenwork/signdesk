<script setup>
defineProps({
  enabled: Boolean,
  pending: Boolean,
  label: { type: String, required: true },
})
const emit = defineEmits(['toggle'])
</script>

<template>
  <el-button class="enabled-action" text :type="enabled ? 'default' : 'primary'"
    :loading="pending" :disabled="pending" :aria-busy="pending"
    :aria-label="`${enabled ? '禁用' : '启用'}${label}`"
    :title="pending ? '正在保存' : `${enabled ? '禁用' : '启用'}${label}`"
    @click="!pending && emit('toggle')">
    {{ enabled ? '禁用' : '启用' }}
  </el-button>
</template>

<style scoped>
.enabled-action { min-height: 32px; min-width: 44px; margin: 0; padding: 7px 10px; font-size: 12px; }
@media (max-width: 760px) { .enabled-action { min-height: 44px; } }
</style>
