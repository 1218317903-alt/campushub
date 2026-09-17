package ai.camphub.community.domain;

/**
 * 标签。
 *
 * @param id        自增主键，仅内部使用
 * @param slug      对外标识，由 {@link Slugifier} 从名称规范化得来。唯一索引建在它上面，
 *                  因此 {@code Java} 与 {@code java} 是同一个标签
 * @param name      展示名，保留创建者输入的大小写
 * @param postCount 使用该标签的未删除帖子数。与 {@link Category#postCount()} 同理，
 *                  是查询得出而非存储的列
 */
public record Tag(
        long id,
        String slug,
        String name,
        long postCount
) {
}
