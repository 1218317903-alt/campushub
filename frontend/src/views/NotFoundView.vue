<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome(): void {
  void router.push('/')
}
</script>

<template>
  <a-result status="404" title="404" subtitle="页面不存在或已被移动。">
    <template #extra>
      <a-button type="primary" @click="goHome">返回概览</a-button>
    </template>
  </a-result>
</template>
