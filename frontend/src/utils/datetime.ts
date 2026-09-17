/**
 * 时间展示。
 *
 * <h2>为什么不做"三分钟前"这类相对时间</h2>
 * 相对时间需要客户端与服务端对"现在"达成一致，而客户端的时钟是用户可改的 ——
 * 一个把系统时间调到明天的人会看到"这条帖子发布于 1 天后"。
 * 相对时间的收益（读起来更快）在这里不成立：帖子的时间精度到分钟已经足够，
 * 而绝对时间不会说谎。
 */

/**
 * 格式化为 `YYYY-MM-DD HH:mm`。
 *
 * <p>显式指定 {@code Asia/Shanghai}：后端的 {@code Instant} 是 UTC，
 * 若不指定时区，展示结果会随运行环境变化 —— 同一份数据在开发机与 CI 上不一样，
 * 是最没必要的排查成本。
 *
 * @param iso ISO-8601 时间串
 * @returns 格式化结果；无法解析时原样返回，便于发现真正的问题
 */
export function formatDateTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

/**
 * 格式化为 `YYYY-MM-DD`。
 *
 * @param iso ISO-8601 时间串
 * @returns 格式化结果
 */
export function formatDate(iso: string): string {
  return formatDateTime(iso).slice(0, 10)
}
