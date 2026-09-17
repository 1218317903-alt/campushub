/**
 * 表单相关的展示工具。
 */

/**
 * 把字段级错误映射成 Arco FormItem 可用的属性。
 *
 * <h2>为什么必须绕一下</h2>
 * 直觉写法是：
 *
 * <pre>
 * &lt;a-form-item :validate-status="err ? 'error' : undefined" :help="err" /&gt;
 * </pre>
 *
 * 它在运行时完全正确，但在本项目<b>编译不过</b> —— 项目开启了
 * {@code exactOptionalPropertyTypes}，而在这个开关下「显式传 {@code undefined}」
 * 与「不传这个属性」是两件不同的事。Arco 把 {@code help} 声明为 {@code string}
 * （可选，但不接受显式 {@code undefined}），于是 {@code help: string | undefined}
 * 无法赋给它。
 *
 * <p>返回一个「有错才带键」的对象再 {@code v-bind} 展开，等价于「没错时根本不传
 * 这两个属性」。语义上也更贴切：没有错误，本来就不存在「错误提示」这个东西。
 *
 * @param errors 字段名 → 提示文案
 * @param field  要取的字段名
 * @returns 有错误时返回 {validateStatus: 'error', help}，无错误时返回空对象
 */
export function fieldFeedback(
  errors: Record<string, string>,
  field: string,
): { validateStatus?: 'error'; help?: string } {
  const message = errors[field]
  if (message === undefined) {
    return {}
  }
  return { validateStatus: 'error', help: message }
}
