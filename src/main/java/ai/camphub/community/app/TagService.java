package ai.camphub.community.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.community.config.CommunityProperties;
import ai.camphub.community.domain.Slugifier;
import ai.camphub.community.domain.Tag;
import ai.camphub.community.infrastructure.TagMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签服务：读取热门标签，以及把用户填写的标签名解析成标签主键。
 *
 * <h2>"查找或创建"为什么必须容忍撞唯一键</h2>
 * 两个用户在同一瞬间发布带同一个新标签的帖子，两边都会查到"这个标签不存在"，
 * 然后都会尝试插入 —— 结果是一个成功、一个撞上 {@code uk_tag_slug}。
 * 这不是缺陷，而是<b>并发下的正常路径</b>。因此这里不去加锁、也不做"先查再插"的双重检查，
 * 而是直接让数据库的唯一键裁决，撞了就改走"查已有记录"的分支。
 *
 * <p>唯一键是数据库级别的保证，不依赖两个请求的执行时序；而任何在应用层的
 * "先检查再操作"在并发下都只是把窗口缩小，不能消除。
 *
 * <h2>为什么依赖事务</h2>
 * 本方法在发布帖子的事务内被调用。若帖子最终发布失败，本次新建的标签也应该一起回滚 ——
 * 否则一个从未被任何内容引用过的标签会留在标签列表里。这依赖于调用方处于事务中，
 * 因此这里用默认传播行为（REQUIRED）而不是 REQUIRES_NEW。
 */
@Service
public class TagService {

    private final TagMapper tagMapper;
    private final CommunityProperties properties;

    /**
     * 构造注入。
     *
     * @param tagMapper  标签数据访问
     * @param properties 社区配置（标签数量上限）
     */
    public TagService(TagMapper tagMapper, CommunityProperties properties) {
        this.tagMapper = tagMapper;
        this.properties = properties;
    }

    /**
     * 列出使用最多的标签。
     *
     * @param limit 返回条数上限
     * @return 标签列表
     */
    @Transactional(readOnly = true)
    public List<Tag> listMostUsed(int limit) {
        return tagMapper.findMostUsed(limit);
    }

    /**
     * 把用户填写的标签名解析成标签主键，不存在的标签会被创建。
     *
     * <h2>去重发生在规范化之后，而不是之前</h2>
     * 用户可能同时填了 {@code Java} 与 {@code java}。若按原字符串去重，
     * 两个都会被保留、都会被解析到同一个 slug，最终写关联表时撞主键
     * {@code (post_id, tag_id)} 而报错。先规范化再按 slug 去重，
     * 这类冲突在进入数据库之前就消失了，而且用户得到的是"同一个标签只算一次"这一正确语义。
     *
     * <p>保留首次出现的展示名：{@code ["Java", "java"]} 的结果是名为 {@code Java} 的标签。
     *
     * @param tagNames 用户填写的标签名，可为 null 或空列表
     * @return 标签主键列表，顺序与首次出现的顺序一致
     * @throws BusinessException 标签数超过上限（{@code 40031}），
     *                           或某个标签名不含任何可用字符（{@code 40032}）
     */
    @Transactional
    public List<Long> resolveOrCreate(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return List.of();
        }

        // LinkedHashMap 保序 + putIfAbsent 保留首次出现的展示名
        Map<String, String> nameBySlug = new LinkedHashMap<>();
        for (String raw : tagNames) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.strip();
            String slug = Slugifier.toSlug(name);
            if (slug.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_TAG,
                        "标签「" + name + "」不含任何字母、数字或文字，无法作为有效标签");
            }
            nameBySlug.putIfAbsent(slug, name);
        }

        int maxTags = properties.post().maxTags();
        if (nameBySlug.size() > maxTags) {
            throw new BusinessException(ErrorCode.INVALID_POST_CONTENT,
                    "标签最多 " + maxTags + " 个，当前 " + nameBySlug.size() + " 个");
        }
        if (nameBySlug.isEmpty()) {
            return List.of();
        }

        Map<String, Long> idBySlug = new HashMap<>();
        for (Tag existing : tagMapper.findBySlugs(nameBySlug.keySet())) {
            idBySlug.put(existing.slug(), existing.id());
        }

        List<String> missing = nameBySlug.keySet().stream()
                .filter(slug -> !idBySlug.containsKey(slug))
                .toList();
        if (!missing.isEmpty()) {
            insertMissing(missing, nameBySlug);
            for (Tag created : tagMapper.findBySlugs(missing)) {
                idBySlug.put(created.slug(), created.id());
            }
        }

        List<Long> ids = new ArrayList<>(nameBySlug.size());
        for (String slug : nameBySlug.keySet()) {
            Long id = idBySlug.get(slug);
            // 走到这里仍然取不到 id 只可能是有人在两次查询之间删掉了刚建的标签，
            // 属于不该发生的情况；跳过它而不是写入 null，避免把问题带进关联表
            if (Objects.nonNull(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * 插入缺失的标签，容忍并发撞键。
     *
     * @param missing     缺失的 slug 列表
     * @param nameBySlug  slug → 展示名
     */
    private void insertMissing(List<String> missing, Map<String, String> nameBySlug) {
        for (String slug : missing) {
            try {
                tagMapper.insert(slug, nameBySlug.get(slug));
            } catch (DuplicateKeyException ignored) {
                // 并发下另一个请求刚建了同一个 slug。这正是"让唯一键裁决"的预期结果：
                // 不重试、不报错，后续重新查询即可拿到它。
                // MySQL 的重复键错误不会使当前事务失效（这点与 PostgreSQL 不同），
                // 因此可以在同一个事务里继续执行后续语句。
            }
        }
    }
}
