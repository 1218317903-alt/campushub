import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import { router } from './router'
import './styles/main.css'

const app = createApp(App)

// Arco 组件由 unplugin-vue-components 在编译期按需引入（见 vite.config.ts），
// 因此这里**不需要** app.use(ArcoVue)，也**不需要**引入整包 arco.css。
// 唯一需要显式 import 的是命令式 API（Message / Notification / Modal），
// 它们不经模板解析，插件无法感知 —— 本项目在真正用到时再单独引入，
// 而不是为了「以后可能要用」提前把它的样式打进产物。
app.use(createPinia())
app.use(router)

app.mount('#app')
