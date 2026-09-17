<script setup lang="ts">
import { InputPassword } from '@arco-design/web-vue'

/**
 * 密码输入框（带明文切换）。
 *
 * <h2>为什么不直接在页面里写 `<a-input-password>`</h2>
 * Arco 的 `InputPassword` 在类型声明里<b>只声明了自己的三个属性</b>
 * （`visibility` / `defaultVisibility` / `invisibleButton`），并没有声明它从
 * `Input` 继承来的 `modelValue`、`placeholder`、`onPressEnter`。运行时这些属性
 * 会被透传下去并正常生效（组件内部把它们交给真正的 `<input>`），于是形成一个
 * 「能跑、但编译不过」的缺口。
 *
 * <p>本项目同时开着 `exactOptionalPropertyTypes` 与 `strictTemplates`，这个缺口会
 * 直接挡在编译期。与其在每个使用处 `as any` 把类型检查一起抹掉，不如把缺口收敛到
 * 这一个文件：下面的断言只补上「它实际具备的对外契约」，页面侧因此仍然享有完整
 * 检查 —— 属性名写错、事件名写错照样会在编译期被发现。
 */
const PasswordField = InputPassword as unknown as new () => {
  $props: {
    modelValue?: string
    'onUpdate:modelValue'?: (value: string) => void
    placeholder?: string
    disabled?: boolean
    onPressEnter?: () => void
  }
}

const props = withDefaults(
  defineProps<{
    modelValue: string
    /** 占位文案 */
    placeholder?: string
    disabled?: boolean
  }>(),
  { placeholder: '', disabled: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  /** 输入框内按下回车 */
  pressEnter: []
}>()
</script>

<template>
  <PasswordField
    :model-value="props.modelValue"
    :placeholder="props.placeholder"
    :disabled="props.disabled"
    @update:model-value="emit('update:modelValue', $event)"
    @press-enter="emit('pressEnter')"
  />
</template>
