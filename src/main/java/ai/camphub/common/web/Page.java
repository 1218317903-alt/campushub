package ai.camphub.common.web;

import java.util.List;

/**
 * 分页结果。
 *
 * <h2>为什么放在 common 而不是某个业务模块</h2>
 * 它是纯粹的传输结构（一个列表 + 三个分页元信息），不含任何业务语义，
 * 也不是"为将来预留的扩展点" —— 从 Phase 03 的第一个列表接口起就真的在被使用。
 * 共享内核里允许存在这类与业务无关的通用结构；被禁止的是把业务逻辑塞进来
 * （由 ArchUnit 的 {@code commonMustNotDependOnAnyBusinessModule} 断言守护）。
 *
 * <h2>为什么成功响应是一个对象而不是裸数组</h2>
 * 分页必须告诉客户端"一共多少条"，否则前端无法决定"下一页"按钮是否可用。
 * 裸数组没有地方承载这个信息，而放进响应头（如 {@code X-Total-Count}）会让它
 * 在跨域、缓存、日志里都变得难以追踪。
 *
 * @param items   当前页内容
 * @param page    当前页码，<b>从 1 开始</b>（从 0 开始的分页在接口文档与前端都比较容易出错）
 * @param size    页大小
 * @param total   符合筛选条件的总条数
 * @param hasNext 是否还有下一页。由服务端算好，避免每个客户端各写一遍
 *                {@code page * size < total} 并各写错一次
 * @param <T>     元素类型
 */
public record Page<T>(
        List<T> items,
        int page,
        int size,
        long total,
        boolean hasNext
) {

    /**
     * 构造分页结果并计算 {@code hasNext}。
     *
     * @param items 当前页内容
     * @param page  当前页码（从 1 开始）
     * @param size  页大小
     * @param total 总条数
     * @param <T>   元素类型
     * @return 分页结果
     */
    public static <T> Page<T> of(List<T> items, int page, int size, long total) {
        return new Page<>(items, page, size, total, (long) page * size < total);
    }
}
