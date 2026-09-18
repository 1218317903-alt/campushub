package ai.camphub.common.web;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应。
 *
 * <h2>为什么它在 common 而不是某个业务模块</h2>
 * 它是分页接口的<b>通用对外信封</b>：社区的帖子列表、空间的成员列表、
 * 文档列表用的是同一个形状。它随 Phase 03 引入时放在 {@code community.api}，
 * Phase 04 迁到这里 —— 若留在社区模块，那么任何新增的分页接口
 * 要么依赖社区模块（一个与它无关的模块），要么复制一份信封，
 * 而两份信封一旦分叉，前端就得为"同一个概念"写两套解析。
 *
 * <p>与 {@link Page} 的区别只有一点：这里用的是<b>对外契约</b>类型，
 * 而 {@code Page} 是应用层内部类型。分开的理由是它们的变化原因不同 ——
 * 应用层可能改成游标分页（不再有 {@code page} 字段），而对外契约的兼容性由版本号管理。
 * 把两者合并，会让一次内部重构直接变成一次契约破坏。
 *
 * @param items   当前页内容
 * @param page    当前页码，从 1 开始
 * @param size    实际生效的页大小（可能与请求值不同，见各模块配置里的 {@code max-page-size}）
 * @param total   符合筛选条件的总条数
 * @param hasNext 是否还有下一页
 * @param <T>     元素类型
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total,
        boolean hasNext
) {

    /**
     * 从应用层分页结果构造。
     *
     * @param page 应用层分页结果
     * @param <T>  元素类型
     * @return 响应体
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.items(), page.page(), page.size(), page.total(), page.hasNext());
    }

    /**
     * 从应用层分页结果构造，并逐个元素做类型转换。
     *
     * <p>有它才有"分页元信息只搬运一次"这件事：否则每个列表接口都要先把
     * {@code items} 映射一遍，再把 page/size/total/hasNext 四个字段手工搬过来 ——
     * 而每搬一次就有一次把 {@code size} 写成 {@code total} 的机会。
     *
     * @param page   应用层分页结果
     * @param mapper 元素转换函数
     * @param <T>    源元素类型
     * @param <R>    目标元素类型
     * @return 响应体
     */
    public static <T, R> PageResponse<R> from(Page<T> page, Function<T, R> mapper) {
        return new PageResponse<>(
                page.items().stream().map(mapper).toList(),
                page.page(),
                page.size(),
                page.total(),
                page.hasNext());
    }
}
