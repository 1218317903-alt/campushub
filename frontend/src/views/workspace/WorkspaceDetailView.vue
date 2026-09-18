<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError, NetworkError } from '@/api/http'
import {
  createInvite,
  deleteWorkspace,
  getWorkspace,
  leaveWorkspace,
  listInvites,
  listMembers,
  removeMember,
  revokeInvite,
  roleLabel,
  updateMemberRole,
  updateWorkspace,
  visibilityLabel,
  type Invite,
  type Member,
  type Workspace,
  type WorkspaceRole,
  type WorkspaceVisibility,
} from '@/api/workspace'
import WorkspaceDocumentPanel from '@/components/WorkspaceDocumentPanel.vue'
import WorkspaceNotePanel from '@/components/WorkspaceNotePanel.vue'
import { formatDateTime } from '@/utils/datetime'

/**
 * 空间详情：文档、协作笔记、成员与邀请。
 *
 * <h2>标签页是懒加载的</h2>
 * 四个标签页各自的数据要发各自的请求。默认全量挂载会让"打开一个空间"变成四次请求，
 * 而用户当次只会看其中一个。懒加载让请求与"用户真的想看什么"对齐。
 *
 * <h2>前端的权限判断只用于决定「显示什么按钮」</h2>
 * 成员/邀请这两个面板没有服务端下发的 `deletableByMe` 之类的标志位，因此这里按角色推导。
 * 它<b>不是</b>权限边界：真正的判定在服务端，前端推导错了只会看到一个 403 提示，
 * 而不会真的做成一件事。文档与笔记则相反 —— 那两个接口明确下发了可否操作的标志，
 * 前端照用即可，不重复推导（否则会出现"前端算出能删、后端拒绝"的不一致）。
 */
const route = useRoute()
const router = useRouter()

const publicId = computed(() => String(route.params.publicId ?? ''))

const workspace = ref<Workspace | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const notFound = ref(false)
const notice = ref('')

/** 当前标签页 */
const activeTab = ref('documents')

/** 编辑空间设置 */
const editing = ref(false)
const savingSettings = ref(false)
const settingsError = ref('')
const editName = ref('')
const editDescription = ref('')
const editVisibility = ref<WorkspaceVisibility>('PRIVATE')

/** 成员 */
const members = ref<Member[]>([])
const membersLoading = ref(false)
const membersError = ref('')

/** 邀请 */
const invites = ref<Invite[]>([])
const invitesLoading = ref(false)
const invitesError = ref('')
const inviteUsername = ref('')
const inviteRole = ref<WorkspaceRole>('MEMBER')
const inviting = ref(false)

/** 我是否是拥有者 / 是否可以管理成员与邀请 */
const isOwner = computed(() => workspace.value?.myRole === 'OWNER')
const canManage = computed(() => {
  const role = workspace.value?.myRole
  return role === 'OWNER' || role === 'ADMIN'
})
/** 拥有者不能退出：那会留下一个无人能管理的空间（后端同样会拒绝） */
const canLeave = computed(() => workspace.value !== null && workspace.value.myRole !== 'OWNER')

/**
 * 加载空间基本信息。失败时区分 404（不可见）与其它错误。
 */
async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await getWorkspace(publicId.value)
    workspace.value = data
    notFound.value = false
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound.value = true
    } else {
      errorMessage.value = describe(error)
    }
  } finally {
    loading.value = false
  }
}

/** 加载成员列表 */
async function loadMembers(): Promise<void> {
  membersLoading.value = true
  membersError.value = ''
  try {
    const { data } = await listMembers(publicId.value)
    members.value = data
  } catch (error) {
    membersError.value = describe(error)
  } finally {
    membersLoading.value = false
  }
}

/** 加载邀请列表 */
async function loadInvites(): Promise<void> {
  invitesLoading.value = true
  invitesError.value = ''
  try {
    const { data } = await listInvites(publicId.value)
    invites.value = data
  } catch (error) {
    invitesError.value = describe(error)
  } finally {
    invitesLoading.value = false
  }
}

/**
 * 标签页切换：第一次进入某个标签页时才去取它的数据。
 *
 * @param key 标签页标识
 */
function onTabChange(key: string | number): void {
  if (key === 'members' && members.value.length === 0) {
    void loadMembers()
  }
  if (key === 'invites' && invites.value.length === 0) {
    void loadInvites()
  }
}

/** 进入编辑态，填入当前设置 */
function startEdit(): void {
  if (!workspace.value) {
    return
  }
  editing.value = true
  settingsError.value = ''
  editName.value = workspace.value.name
  editDescription.value = workspace.value.description ?? ''
  editVisibility.value = workspace.value.visibility
}

/**
 * 保存空间设置（仅拥有者）。
 */
async function saveSettings(): Promise<void> {
  if (editName.value.trim() === '') {
    settingsError.value = '请填写空间名称'
    return
  }
  savingSettings.value = true
  settingsError.value = ''
  try {
    const { data } = await updateWorkspace(publicId.value, {
      name: editName.value.trim(),
      description: editDescription.value.trim() === '' ? null : editDescription.value.trim(),
      visibility: editVisibility.value,
    })
    workspace.value = data
    editing.value = false
    notice.value = '设置已保存。'
  } catch (error) {
    settingsError.value = describe(error)
  } finally {
    savingSettings.value = false
  }
}

/**
 * 邀请一个已注册用户。
 *
 * <p>邀请码在创建后直接显示出来：当前阶段没有通知机制，邀请人需要自行转达。
 */
async function submitInvite(): Promise<void> {
  if (inviteUsername.value.trim() === '') {
    invitesError.value = '请填写对方的登录名'
    return
  }
  inviting.value = true
  invitesError.value = ''
  try {
    const { data } = await createInvite(publicId.value, {
      username: inviteUsername.value.trim(),
      role: inviteRole.value,
    })
    inviteUsername.value = ''
    notice.value = `已邀请 ${data.invitee.nickname}，邀请码：${data.code}`
    await loadInvites()
  } catch (error) {
    invitesError.value = describe(error)
  } finally {
    inviting.value = false
  }
}

/**
 * 撤销一条邀请。
 *
 * @param invite 邀请
 */
async function cancelInvite(invite: Invite): Promise<void> {
  invitesError.value = ''
  try {
    await revokeInvite(publicId.value, invite.code)
    await loadInvites()
  } catch (error) {
    invitesError.value = describe(error)
  }
}

/**
 * 修改成员角色。
 *
 * @param member 成员
 * @param role   新角色
 */
async function changeRole(member: Member, role: WorkspaceRole): Promise<void> {
  membersError.value = ''
  try {
    await updateMemberRole(publicId.value, member.userPublicId, role)
    await loadMembers()
  } catch (error) {
    membersError.value = describe(error)
    // 本地先改回旧值：否则界面上会停在一个并未生效的角色上
    await loadMembers()
  }
}

/**
 * 移除成员。
 *
 * @param member 成员
 */
async function dropMember(member: Member): Promise<void> {
  membersError.value = ''
  try {
    await removeMember(publicId.value, member.userPublicId)
    await loadMembers()
  } catch (error) {
    membersError.value = describe(error)
  }
}

/**
 * 退出空间，成功后回列表。
 */
async function leave(): Promise<void> {
  try {
    await leaveWorkspace(publicId.value)
    void router.push({ name: 'spaces' })
  } catch (error) {
    errorMessage.value = describe(error)
  }
}

/**
 * 删除空间，成功后回列表。
 */
async function remove(): Promise<void> {
  try {
    await deleteWorkspace(publicId.value)
    void router.push({ name: 'spaces' })
  } catch (error) {
    errorMessage.value = describe(error)
  }
}

/**
 * 复制文本到剪贴板。
 *
 * @param text 文本
 */
async function copy(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    notice.value = '邀请码已复制。'
  } catch {
    notice.value = '当前环境不允许自动复制，请手动选中邀请码。'
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
  <div class="space">
    <a-spin :loading="loading" class="space__spin">
      <a-result
        v-if="notFound"
        status="404"
        title="空间不存在，或者你不是它的成员"
        subtitle="不属于你的空间在响应上与不存在不作区分，这是刻意的。"
      >
        <template #extra>
          <a-button type="primary" @click="router.push({ name: 'spaces' })">回到我的空间</a-button>
        </template>
      </a-result>

      <template v-else-if="workspace">
        <a-alert v-if="errorMessage" type="error" :title="errorMessage" closable @close="errorMessage = ''" />
        <a-alert v-if="notice" type="info" :title="notice" closable @close="notice = ''" />

        <header class="space__head">
          <div class="space__head-main">
            <h1 class="space__title">{{ workspace.name }}</h1>
            <p class="space__desc">{{ workspace.description || '（未填写简介）' }}</p>
            <div class="space__meta">
              <a-tag size="small" color="arcoblue">{{ visibilityLabel(workspace.visibility) }}</a-tag>
              <a-tag size="small">{{ roleLabel(workspace.myRole) }}</a-tag>
              <span>创建于 {{ formatDateTime(workspace.createdAt) }}</span>
            </div>
          </div>
          <a-space wrap>
            <a-button v-if="isOwner" size="small" @click="startEdit">空间设置</a-button>
            <a-popconfirm
              v-if="canLeave"
              content="退出后需要重新获得邀请才能进来。"
              type="warning"
              ok-text="退出"
              cancel-text="取消"
              @ok="leave"
            >
              <a-button size="small">退出空间</a-button>
            </a-popconfirm>
            <a-popconfirm
              v-if="isOwner"
              content="删除空间会同时清除成员、笔记与文档记录，不可恢复。"
              type="warning"
              ok-text="删除"
              cancel-text="取消"
              @ok="remove"
            >
              <a-button size="small" status="danger">删除空间</a-button>
            </a-popconfirm>
          </a-space>
        </header>

        <a-card v-if="editing" class="space__card" title="空间设置">
          <a-form :model="{ editName, editDescription, editVisibility }" layout="vertical">
            <a-form-item label="名称">
              <a-input v-model="editName" :max-length="80" />
            </a-form-item>
            <a-form-item label="简介">
              <a-textarea
                v-model="editDescription"
                :max-length="500"
                :auto-size="{ minRows: 2, maxRows: 4 }"
              />
            </a-form-item>
            <a-form-item label="可见性">
              <a-select v-model="editVisibility">
                <a-option value="PRIVATE">私有空间</a-option>
                <a-option value="TEAM">团队空间</a-option>
              </a-select>
            </a-form-item>
          </a-form>
          <a-alert v-if="settingsError" type="error" :title="settingsError" />
          <a-space>
            <a-button type="primary" size="small" :loading="savingSettings" @click="saveSettings">保存</a-button>
            <a-button size="small" @click="editing = false">取消</a-button>
          </a-space>
        </a-card>

        <a-tabs v-model:active-key="activeTab" lazy-load @change="onTabChange">
          <a-tab-pane key="documents" title="文档">
            <WorkspaceDocumentPanel :public-id="publicId" />
          </a-tab-pane>

          <a-tab-pane key="notes" title="协作笔记">
            <WorkspaceNotePanel :public-id="publicId" />
          </a-tab-pane>

          <a-tab-pane key="members" title="成员">
            <a-alert v-if="membersError" type="error" :title="membersError" closable @close="membersError = ''" />
            <a-spin :loading="membersLoading" class="space__spin">
              <a-empty v-if="members.length === 0 && !membersLoading" description="成员列表待加载" />
              <table v-else class="space__table">
                <thead>
                  <tr>
                    <th>成员</th>
                    <th>角色</th>
                    <th>加入时间</th>
                    <th class="space__col-actions">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="member in members" :key="member.userPublicId">
                    <td>{{ member.nickname }}</td>
                    <td>
                      <a-select
                        v-if="canManage && member.role !== 'OWNER'"
                        :model-value="member.role"
                        size="small"
                        class="space__role-select"
                        @change="(value) => changeRole(member, value as WorkspaceRole)"
                      >
                        <a-option value="ADMIN">管理员</a-option>
                        <a-option value="MEMBER">成员</a-option>
                      </a-select>
                      <a-tag v-else size="small">{{ roleLabel(member.role) }}</a-tag>
                    </td>
                    <td>{{ formatDateTime(member.joinedAt) }}</td>
                    <td class="space__col-actions">
                      <a-popconfirm
                        v-if="canManage && member.role !== 'OWNER'"
                        content="被移除后他将无法再访问这个空间。"
                        type="warning"
                        ok-text="移除"
                        cancel-text="取消"
                        @ok="dropMember(member)"
                      >
                        <a-button size="mini" status="danger">移除</a-button>
                      </a-popconfirm>
                      <span v-else class="space__hint">—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p class="space__hint">成员上限为 50 人（不含拥有者）。</p>
            </a-spin>
          </a-tab-pane>

          <a-tab-pane key="invites" title="邀请">
            <a-alert v-if="invitesError" type="error" :title="invitesError" closable @close="invitesError = ''" />

            <template v-if="canManage">
              <div class="space__invite-form">
                <a-input
                  v-model="inviteUsername"
                  class="space__invite-name"
                  placeholder="对方的登录名"
                  allow-clear
                />
                <a-select v-model="inviteRole" class="space__invite-role">
                  <a-option value="MEMBER">成员</a-option>
                  <a-option value="ADMIN">管理员</a-option>
                </a-select>
                <a-button size="small" type="primary" :loading="inviting" @click="submitInvite">邀请</a-button>
                <span class="space__hint">只能邀请已注册的用户；邀请码 72 小时内有效。</span>
              </div>
            </template>
            <a-alert v-else type="info" title="只有拥有者与管理员可以邀请成员。" />

            <a-spin :loading="invitesLoading" class="space__spin">
              <a-empty v-if="invites.length === 0 && !invitesLoading" description="还没有发出过邀请" />
              <table v-else class="space__table">
                <thead>
                  <tr>
                    <th>被邀请人</th>
                    <th>角色</th>
                    <th>邀请码</th>
                    <th>状态</th>
                    <th>有效期至</th>
                    <th class="space__col-actions">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="invite in invites" :key="invite.code">
                    <td>{{ invite.invitee.nickname }}</td>
                    <td>{{ roleLabel(invite.role) }}</td>
                    <td>
                      <code class="space__code">{{ invite.code }}</code>
                      <a-button size="mini" type="text" @click="copy(invite.code)">复制</a-button>
                    </td>
                    <td>{{ invite.status }}</td>
                    <td>{{ formatDateTime(invite.expiresAt) }}</td>
                    <td class="space__col-actions">
                      <a-popconfirm
                        v-if="canManage && invite.status === 'PENDING'"
                        content="撤销后这串邀请码立即失效。"
                        type="warning"
                        ok-text="撤销"
                        cancel-text="取消"
                        @ok="cancelInvite(invite)"
                      >
                        <a-button size="mini" status="danger">撤销</a-button>
                      </a-popconfirm>
                      <span v-else class="space__hint">—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </a-spin>
          </a-tab-pane>
        </a-tabs>
      </template>
    </a-spin>
  </div>
</template>

<style scoped>
.space {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.space__spin {
  display: block;
}

.space__head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.space__head-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.space__title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}

.space__desc {
  margin: 0;
  color: var(--ch-text-secondary);
}

.space__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  color: var(--ch-text-tertiary);
  font-size: 12px;
}

.space__card {
  border: 1px solid var(--ch-border);
  border-radius: var(--ch-radius);
}

.space__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.space__table th,
.space__table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ch-border);
  text-align: left;
  vertical-align: middle;
}

.space__table thead th {
  color: var(--ch-text-tertiary);
  font-weight: 500;
}

.space__col-actions {
  width: 140px;
}

.space__role-select {
  max-width: 130px;
}

.space__invite-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.space__invite-name {
  max-width: 220px;
}

.space__invite-role {
  max-width: 130px;
}

.space__code {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--ch-bg-page);
  font-size: 12px;
}

.space__hint {
  color: var(--ch-text-tertiary);
  font-size: 12px;
}
</style>
