<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { ApiError, NetworkError } from '@/api/http'
import {
  acceptInvite,
  createWorkspace,
  listWorkspaces,
  roleLabel,
  visibilityLabel,
  type Workspace,
  type WorkspaceVisibility,
} from '@/api/workspace'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/datetime'

/**
 * 我的空间。
 *
 * <h2>为什么把「用邀请码加入」放在这一页而不是一个独立入口</h2>
 * 被邀请人的处境是：他手里有一串码，但列表里还没有那个空间。
 * 把这件事实成一个需要先找到入口的独立功能，等于要求用户在自己都还没进去的地方
 * 先学会导航。放在列表页顶部，他看到「我的空间」为空时，旁边就是「加入一个空间」。
 *
 * <h2>创建空间与加入空间都是「低频但不可省」的动作</h2>
 * 它们不该占据首屏，也不该被藏进一个只有开发者找得到的菜单。这里用可展开的行内表单：
 * 不占地方，但用户需要时一眼能看见。
 */
const router = useRouter()
const auth = useAuthStore()

const workspaces = ref<Workspace[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const loading = ref(false)
const errorMessage = ref('')

/** 新建空间的表单是否展开 */
const createOpen = ref(false)
const creating = ref(false)
const createError = ref('')
const name = ref('')
const description = ref('')
const visibility = ref<WorkspaceVisibility>('PRIVATE')

/** 邀请码 */
const inviteCode = ref('')
const accepting = ref(false)
const inviteError = ref('')
const inviteNotice = ref('')

/**
 * 加载空间列表。
 */
async function load(): Promise<void> {
  if (!auth.isLoggedIn) {
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await listWorkspaces(page.value, pageSize)
    workspaces.value = data.items
    total.value = data.total
  } catch (error) {
    errorMessage.value = describe(error)
  } finally {
    loading.value = false
  }
}

/**
 * 翻页。
 *
 * @param next 目标页码
 */
function changePage(next: number): void {
  page.value = next
  void load()
}

/**
 * 创建空间。
 */
async function submitCreate(): Promise<void> {
  if (name.value.trim() === '') {
    createError.value = '请填写空间名称'
    return
  }
  creating.value = true
  createError.value = ''
  try {
    const { data } = await createWorkspace({
      name: name.value.trim(),
      description: description.value.trim() === '' ? null : description.value.trim(),
      visibility: visibility.value,
    })
    createOpen.value = false
    name.value = ''
    description.value = ''
    visibility.value = 'PRIVATE'
    // 直接进入新空间：创建之后用户唯一想做的事就是往里放东西
    void router.push({ name: 'workspace-detail', params: { publicId: data.publicId } })
  } catch (error) {
    createError.value = describe(error)
  } finally {
    creating.value = false
  }
}

/**
 * 兑换邀请码。
 *
 * <p>成功后跳进那个空间，而不是刷新列表 —— 用户手里的码指向一个明确的目标，
 * 让他自己在列表里再找一遍是多余的。
 */
async function submitAccept(): Promise<void> {
  const code = inviteCode.value.trim()
  if (code === '') {
    inviteError.value = '请填入邀请码'
    return
  }
  accepting.value = true
  inviteError.value = ''
  inviteNotice.value = ''
  try {
    const { data } = await acceptInvite(code)
    inviteCode.value = ''
    void router.push({ name: 'workspace-detail', params: { publicId: data.publicId } })
  } catch (error) {
    // 邀请码的失败原因（过期/已撤销/不是发给你的）对用户是同一件事：
    // 这串码现在用不了。分得太细反而像是在提示"你猜错了原因"。
    inviteError.value = describe(error)
  } finally {
    accepting.value = false
  }
}

/**
 * 把异常翻译成提示文案。
 *
 * @param error 异常
 * @returns 文案
 */
function describe(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  if (error instanceof NetworkError) {
    return `${error.message}。请确认后端已启动。`
  }
  return '发生未知错误'
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="spaces">
    <header class="spaces__head">
      <h1 class="spaces__title">我的空间</h1>
      <p class="spaces__desc">
        空间是私有的协作容器：成员、协作笔记与文档都在里面，默认不对社区公开。
      </p>
    </header>

    <a-result
      v-if="!auth.isLoggedIn"
      status="403"
      title="需要登录"
      subtitle="空间属于账号级数据，登录后才能查看。"
    >
      <template #extra>
        <a-button type="primary" @click="router.push({ name: 'login', query: { redirect: '/spaces' } })">
          去登录
        </a-button>
      </template>
    </a-result>

    <template v-else>
      <a-alert v-if="errorMessage" type="error" :title="errorMessage" closable @close="errorMessage = ''" />

      <section class="spaces__panel">
        <div class="spaces__panel-head">
          <a-button size="small" type="primary" @click="createOpen = !createOpen">
            {{ createOpen ? '收起' : '新建空间' }}
          </a-button>
          <span class="spaces__hint">名称与可见性之后都能改。</span>
        </div>

        <a-form
          v-if="createOpen"
          class="spaces__form"
          :model="{ name, description, visibility }"
          layout="vertical"
          @submit-success="submitCreate"
        >
          <a-form-item field="name" label="空间名称">
            <a-input v-model="name" placeholder="例如：操作系统课程小组" :max-length="80" />
          </a-form-item>
          <a-form-item field="description" label="简介（可选）">
            <a-textarea
              v-model="description"
              placeholder="这个空间用来做什么"
              :max-length="500"
              :auto-size="{ minRows: 2, maxRows: 4 }"
            />
          </a-form-item>
          <a-form-item field="visibility" label="可见性">
            <a-select v-model="visibility">
              <a-option value="PRIVATE">私有空间 —— 仅成员可见</a-option>
              <a-option value="TEAM">团队空间 —— 供小组长期协作</a-option>
            </a-select>
          </a-form-item>
          <a-alert v-if="createError" type="error" :title="createError" />
          <a-space>
            <a-button type="primary" :loading="creating" @click="submitCreate">创建并进入</a-button>
            <a-button @click="createOpen = false">取消</a-button>
          </a-space>
        </a-form>
      </section>

      <section class="spaces__panel">
        <div class="spaces__panel-head">
          <strong class="spaces__panel-title">用邀请码加入</strong>
          <span class="spaces__hint">
            邀请码由空间成员在空间内的「邀请」页生成，当前没有通知机制，需要对方自行转达。
          </span>
        </div>
        <div class="spaces__invite">
          <a-input
            v-model="inviteCode"
            class="spaces__invite-input"
            placeholder="粘贴邀请码"
            allow-clear
            @press-enter="submitAccept"
          />
          <a-button type="outline" :loading="accepting" @click="submitAccept">加入</a-button>
        </div>
        <a-alert v-if="inviteError" type="error" :title="inviteError" />
        <a-alert v-if="inviteNotice" type="success" :title="inviteNotice" />
      </section>

      <a-spin :loading="loading" class="spaces__spin">
        <a-empty v-if="workspaces.length === 0 && !loading" description="还没有加入任何空间">
          <a-button type="primary" @click="createOpen = true">新建一个空间</a-button>
        </a-empty>

        <div v-else class="spaces__grid">
          <RouterLink
            v-for="item in workspaces"
            :key="item.publicId"
            class="spaces__card"
            :to="{ name: 'workspace-detail', params: { publicId: item.publicId } }"
          >
            <div class="spaces__card-head">
              <span class="spaces__card-name">{{ item.name }}</span>
              <a-tag size="small" :color="item.myRole === 'MEMBER' ? 'gray' : 'arcoblue'">
                {{ roleLabel(item.myRole) }}
              </a-tag>
            </div>
            <p class="spaces__card-desc">{{ item.description || '（未填写简介）' }}</p>
            <div class="spaces__card-foot">
              <span>{{ visibilityLabel(item.visibility) }}</span>
              <span>创建于 {{ formatDateTime(item.createdAt) }}</span>
            </div>
          </RouterLink>
        </div>

        <a-pagination
          v-if="total > pageSize"
          class="spaces__pagination"
          :total="total"
          :current="page"
          :page-size="pageSize"
          show-total
          @change="changePage"
        />
      </a-spin>
    </template>
  </div>
</template>

<style scoped>
.spaces {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.spaces__title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
}

.spaces__desc {
  margin: 0;
  color: var(--ch-text-secondary);
}

.spaces__panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
}

.spaces__panel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.spaces__panel-title {
  font-size: 14px;
}

.spaces__hint {
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.spaces__form {
  display: block;
}

.spaces__invite {
  display: flex;
  gap: 8px;
}

.spaces__invite-input {
  max-width: 320px;
}

.spaces__spin {
  display: block;
}

.spaces__grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
}

.spaces__card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
  background: var(--ch-bg-surface);
  color: inherit;
  text-decoration: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.spaces__card:hover {
  border-color: var(--ch-brand);
  box-shadow: 0 2px 10px rgb(0 0 0 / 6%);
}

.spaces__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.spaces__card-name {
  overflow: hidden;
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spaces__card-desc {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  min-height: 40px;
  color: var(--ch-text-secondary);
  font-size: 13px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.spaces__card-foot {
  display: flex;
  justify-content: space-between;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.spaces__pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
