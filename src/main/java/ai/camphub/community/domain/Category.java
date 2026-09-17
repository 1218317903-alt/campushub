package ai.camphub.community.domain;

/**
 * 板块。
 *
 * @param id          自增主键，仅内部使用（帖子表通过它关联）
 * @param slug        对外标识，URL 安全。接口与前端一律用它，不暴露 id
 * @param name        展示名
 * @param description 一句话说明，可为空
 * @param sortOrder   展示排序，小的在前。刻意用显式排序值而不是按 id 或名称排序 ——
 *                    运营希望调整板块顺序时不应该需要改数据行
 * @param postCount   该板块下未被删除的帖子数。<b>不是表里的列</b>，
 *                    由列表查询 COUNT 得出。之所以放在这个记录里：板块只有几十个，
 *                    一次带 COUNT 的查询即可，不必为此再定义一个"带计数的板块"类型，
 *                    那只会让调用方多做一次组装
 */
public record Category(
        long id,
        String slug,
        String name,
        String description,
        int sortOrder,
        long postCount
) {
}
