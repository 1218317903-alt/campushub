<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError, NetworkError } from '@/api/http'
import PasswordInput from '@/components/PasswordInput.vue'
import { useAuthStore } from '@/stores/auth'
import { fieldFeedback } from '@/utils/form'

/**
 * 登录 / 注册。
 *
 * <h2>为什么合并在一个页面</h2>
 * 这两个动作的用户意图是同一个：「我要能用这个站点」。分成两个页面意味着
 * 用户在"我到底该点哪个"上多花一次判断，而失败时还要退回上一页再试另一个。
 *
 * <h2>错误提示按来源区分，不合并成一句"失败了"</h2>
 * 服务端明确拒绝（40103 用户名或密码错误）、字段不合法（40000，带 details）、
 * 连不上后端（NetworkError）三者的处置完全不同：前者要改密码，中间要改字段，
 * 后者要去看后端有没有起来。混成一句会让真实故障无法定位。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const mode = ref<'login' | 'register'>('login')

const identifier = ref('')
const password = ref('')
const username = ref('')
const email = ref('')
const nickname = ref('')
const confirmPassword = ref('')

const submitting = ref(false)
const errorMessage = ref('')
const fieldErrors = ref<Record<string, string>>({})

const redirectTarget = computed(() => {
  const target = route.query.redirect
  return typeof target === 'string' && target.startsWith('/') ? target : '/community'
})

/** 切换模式时清掉上一次的错误：留在界面上会让人以为新表单也有问题 */
watch(mode, () => {
  errorMessage.value = ''
  fieldErrors.value = {}
})

/**
 * 处理失败。
 *
 * @param error 异常
 */
function applyError(error: unknown): void {
  fieldErrors.value = {}
  if (error instanceof ApiError) {
    errorMessage.value = error.message
    fieldErrors.value = error.fieldMessages()
  } else if (error instanceof NetworkError) {
    errorMessage.value = `${error.message}。请确认后端已启动（默认 127.0.0.1:8080）。`
  } else {
    errorMessage.value = '发生未知错误'
  }
}

/**
 * 登录。
 */
async function submitLogin(): Promise<void> {
  if (!identifier.value.trim() || !password.value) {
    errorMessage.value = '请填写用户名（或邮箱）与密码'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.login(identifier.value.trim(), password.value)
    void router.replace(redirectTarget.value)
  } catch (error) {
    applyError(error)
  } finally {
    submitting.value = false
  }
}

/**
 * 注册。
 */
async function submitRegister(): Promise<void> {
  if (!username.value.trim() || !email.value.trim() || !password.value) {
    errorMessage.value = '请填写登录名、邮箱与密码'
    return
  }
  // 两次输入校验放在前端：服务端不该收到一个"两次密码不一致"的请求，
  // 那既多一次往返，也会让服务端多一条本不属于它的规则
  if (password.value !== confirmPassword.value) {
    errorMessage.value = '两次输入的密码不一致'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.register(username.value.trim(), email.value.trim(), password.value, nickname.value.trim())
    void router.replace(redirectTarget.value)
  } catch (error) {
    applyError(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth">
    <div class="auth__card">
      <header class="auth__head">
        <h1 class="auth__title">{{ mode === 'login' ? '登录 CampusHub' : '注册 CampusHub' }}</h1>
        <p class="auth__desc">
          {{
            mode === 'login'
              ? '浏览社区不需要登录，参与讨论需要。'
              : '注册成功后会自动登录，无需再输入一次。'
          }}
        </p>
      </header>

      <div class="auth__mode">
        <button
          class="auth__mode-btn"
          :class="{ 'auth__mode-btn--active': mode === 'login' }"
          type="button"
          @click="mode = 'login'"
        >
          登录
        </button>
        <button
          class="auth__mode-btn"
          :class="{ 'auth__mode-btn--active': mode === 'register' }"
          type="button"
          @click="mode = 'register'"
        >
          注册
        </button>
      </div>

      <a-alert
        v-if="errorMessage"
        class="auth__alert"
        type="error"
        :title="errorMessage"
        closable
        @close="errorMessage = ''"
      />

      <!--
        Arco 的 Form 要求传入 model。本页刻意不用 Arco 的字段校验规则 ——
        校验以服务端返回的字段错误为准（见 applyError），这里只是把字段聚合起来
        满足契约。这样"哪些字段不合法"只有一个来源，不会出现前后端两套规则的漂移。
      -->
      <a-form
        v-if="mode === 'login'"
        :model="{ identifier, password }"
        layout="vertical"
        @submit.prevent="submitLogin"
      >
        <a-form-item label="用户名或邮箱" v-bind="fieldFeedback(fieldErrors, 'identifier')">
          <a-input v-model="identifier" placeholder="用户名或邮箱，忽略大小写" @press-enter="submitLogin" />
        </a-form-item>

        <a-form-item label="密码" v-bind="fieldFeedback(fieldErrors, 'password')">
          <PasswordInput v-model="password" placeholder="密码" @press-enter="submitLogin" />
        </a-form-item>

        <a-button class="auth__submit" type="primary" long :loading="submitting" @click="submitLogin">
          登录
        </a-button>
      </a-form>

      <a-form
        v-else
        :model="{ username, email, nickname, password, confirmPassword }"
        layout="vertical"
        @submit.prevent="submitRegister"
      >
        <a-form-item
          label="登录名"
          extra="只能包含字母、数字、下划线和连字符，长度 3~32 位。登录名不可修改。"
          v-bind="fieldFeedback(fieldErrors, 'username')"
        >
          <a-input v-model="username" placeholder="登录名" />
        </a-form-item>

        <a-form-item label="邮箱" v-bind="fieldFeedback(fieldErrors, 'email')">
          <a-input v-model="email" placeholder="用于在忘记密码时找回账号" />
        </a-form-item>

        <a-form-item
          label="昵称"
          extra="可为空。为空时使用登录名作为展示名。"
          v-bind="fieldFeedback(fieldErrors, 'nickname')"
        >
          <a-input v-model="nickname" placeholder="其他人看到的展示名" />
        </a-form-item>

        <a-form-item
          label="密码"
          extra="至少 10 位，不能是常见弱密码，也不能包含登录名或邮箱。不强制大小写与符号组合。"
          v-bind="fieldFeedback(fieldErrors, 'password')"
        >
          <PasswordInput v-model="password" placeholder="密码" />
        </a-form-item>

        <a-form-item label="确认密码">
          <PasswordInput v-model="confirmPassword" placeholder="再输入一次" @press-enter="submitRegister" />
        </a-form-item>

        <a-button class="auth__submit" type="primary" long :loading="submitting" @click="submitRegister">
          注册并登录
        </a-button>
      </a-form>
    </div>
  </div>
</template>

<style scoped>
.auth {
  display: flex;
  justify-content: center;
  padding-top: 24px;
}

.auth__card {
  width: 100%;
  max-width: 440px;
  padding: 28px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
}

.auth__head {
  margin-bottom: 18px;
}

.auth__title {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 600;
}

.auth__desc {
  margin: 0;
  color: var(--ch-text-secondary);
}

.auth__mode {
  display: flex;
  gap: 4px;
  margin-bottom: 18px;
  padding: 3px;
  border-radius: var(--ch-radius);
  background: var(--ch-bg-page);
}

.auth__mode-btn {
  flex: 1;
  padding: 6px 0;
  border: none;
  border-radius: 6px;
  background: none;
  color: var(--ch-text-secondary);
  cursor: pointer;
  font-size: 14px;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.auth__mode-btn--active {
  background: var(--ch-bg-surface);
  color: var(--ch-brand);
  font-weight: 500;
}

.auth__alert {
  margin-bottom: 16px;
}

.auth__submit {
  margin-top: 4px;
}
</style>
