<script setup lang="ts">
import zhCN from '@arco-design/web-vue/es/locale/lang/zh-cn'
import { onMounted } from 'vue'

import AppLayout from '@/layouts/AppLayout.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

/**
 * 恢复登录态。
 *
 * <p>放在这里（应用启动时一次）而不是路由守卫里：守卫每次跳转都会执行，
 * 而恢复会话要请求一次用户资料 —— 那会让"点一次导航就发一次资料请求"。
 * 守卫只做体验层面的判断（未登录就别进需要登录的页面），真正的鉴权在服务端。
 *
 * <p>不 await、不阻塞首屏：本地有令牌就先按已登录渲染，资料到了再补上。
 * 令牌无效时 HTTP 层会给出结论并清掉本地状态，界面随之回到未登录。
 */
onMounted(() => {
  void auth.restore()
})
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
