<script setup lang="ts">
import zhCN from '@arco-design/web-vue/es/locale/lang/zh-cn'

import AppLayout from '@/layouts/AppLayout.vue'
</script>

<template>
  <!--
    ConfigProvider 放在最外层：Arco 的组件文案（分页、空状态、日期选择等）都从 locale 读取，
    在这里统一设为中文，避免每个页面各自传参。
  -->
  <a-config-provider :locale="zhCN">
    <AppLayout />
  </a-config-provider>
</template>
