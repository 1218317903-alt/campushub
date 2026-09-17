package ai.camphub.bootstrap;

import ai.camphub.community.app.CommentService;
import ai.camphub.community.app.CommentView;
import ai.camphub.community.app.FeedQuery;
import ai.camphub.community.app.PostCommand;
import ai.camphub.community.app.PostService;
import ai.camphub.community.app.ReactionService;
import ai.camphub.identity.app.AuthService;
import ai.camphub.identity.app.UserDirectory;
import ai.camphub.identity.domain.UserBrief;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 合成演示数据生成器：让"从零部署后第一次打开首页"看到的是有内容的系统，而不是空壳。
 *
 * <h2>它为什么不写在 Flyway 迁移里</h2>
 * 演示数据进入迁移会污染**所有**环境，包括集成测试：测试里"列表应该有 N 条"会变成
 * 对种子行数的隐式依赖，一改种子就红一片，而失败信息完全指不到真正被改的地方。
 * 迁移只负责结构性数据（板块、标签这些"没有它们就无法运行"的行），
 * 演示内容由一个可开关的组件生成。
 *
 * <h2>它为什么是一个 ApplicationRunner，而不是某个模块里的服务</h2>
 * 它跨模块：要建 identity 的账号，也要建 community 的内容。
 * 放进任何一个业务模块都会让那个模块莫名其妙地依赖另一个模块的写接口，
 * 甚至形成循环依赖（ArchUnit 会直接让构建失败）。
 * 放在这里作为<b>组装点</b>，依赖方向是单向的：组装点 → 各业务模块的公开应用服务。
 *
 * <h2>它只使用各模块的公开应用服务，不碰任何数据表</h2>
 * 建账号走 {@link AuthService#register}，发帖走 {@link PostService#create}，
 * 评论走 {@link CommentService#create}，点赞走 {@link ReactionService#like}。
 * 这不只是为了守规矩，还有一个实际好处：演示数据因此走的是与真实用户完全相同的
 * 代码路径 —— 密码会被真正哈希、审计会被真正记录、Markdown 会被真正净化。
 * 反过来，若它直接 INSERT，它就会绕过这些规则，生成一批"只在这个生成器眼里合法"的数据，
 * 而它恰恰是别人第一次接触这个系统时看到的东西。
 *
 * <h2>幂等性：靠"已经有没有内容"判断，而不是靠开关状态</h2>
 * 判据是 community 里有没有帖子。有就整体跳过，没有就开始生成。
 * 账号部分额外做了逐账号的幂等（已存在的账号直接复用），
 * 于是"账号建好了但内容没建成"这种情况重跑也能收敛，而不会撞"用户名已被使用"。
 *
 * <p><b>已知限制</b>：若生成过程在创建内容的中途失败（例如连接中断），
 * 数据库里会留下部分内容，重跑会因"已经有帖子"而跳过。
 * 这种状态不会损坏任何东西，但需要人工重建数据库才能得到完整的一份 ——
 * 对本地演示环境来说这是可接受的取舍（换取的是一次运行内不需要大事务与补偿逻辑）。
 * 刻意<b>不</b>把整个过程包成一个大事务：几百次写操作放进一个事务会长时间持有大量行锁，
 * 而且审计记录走独立事务（REQUIRES_NEW），本来就不会跟着回滚，包大事务只会造成假一致。
 *
 * <h2>三道闸门，且默认关闭</h2>
 * <ol>
 *   <li>{@code app.demo-seed.enabled} 默认为 {@code false}；</li>
 *   <li>显式开启后，仍<b>拒绝在 prod profile 下执行</b>；</li>
 *   <li>执行时把生成的账号与口令打印到启动日志 —— 演示账号是公开入口，
 *       不该表现得像个秘密。</li>
 * </ol>
 */
@Component
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    /**
     * 演示账号的会话设备标识。
     *
     * <p>它会出现在"登录设备"列表里。写成一个可辨认的名字，是为了让人在查看
     * 会话列表时能一眼看出这些会话是生成器建的，而不是某个真实客户端。
     */
    private static final String DEMO_DEVICE = "演示数据生成器";

    private final DemoSeedProperties properties;
    private final Environment environment;
    private final AuthService authService;
    private final UserDirectory userDirectory;
    private final PostService postService;
    private final CommentService commentService;
    private final ReactionService reactionService;

    /**
     * 构造注入。
     *
     * @param properties      演示数据配置
     * @param environment     运行环境（用于判断 profile）
     * @param authService     identity 的注册入口
     * @param userDirectory   identity 的用户目录（把登录名换算成内部主键）
     * @param postService     帖子服务
     * @param commentService  评论服务
     * @param reactionService 互动服务
     */
    public DemoSeedRunner(DemoSeedProperties properties,
                          Environment environment,
                          AuthService authService,
                          UserDirectory userDirectory,
                          PostService postService,
                          CommentService commentService,
                          ReactionService reactionService) {
        this.properties = properties;
        this.environment = environment;
        this.authService = authService;
        this.userDirectory = userDirectory;
        this.postService = postService;
        this.commentService = commentService;
        this.reactionService = reactionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            // 默认路径，静默跳过。这里用 debug 而不是 info：一个默认关闭的组件
            // 不该在每次启动时都刷一行日志，那会训练人忽略启动日志
            log.debug("演示数据生成器未启用（app.demo-seed.enabled=false）");
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            log.error("演示数据生成器被要求在 prod profile 下运行，已拒绝执行。"
                    + "它会往业务表写入一批看起来像真实用户的假数据，清理代价远高于本地便利。");
            return;
        }

        requireValidConfiguration();

        if (hasAnyContent()) {
            log.info("演示数据生成器：community 中已存在帖子，跳过生成。"
                    + "（若上一次生成中途失败、内容不完整，请重建数据库后重启）");
            return;
        }

        long startedAt = System.nanoTime();
        List<Long> authors = ensureAuthors();
        List<String> postPublicIds = createPosts(authors);
        int comments = createComments(postPublicIds, authors);
        int reactions = createReactions(postPublicIds, authors);
        int views = createViews(postPublicIds, authors);

        log.info("演示数据生成完成：作者 {} 位、帖子 {} 条、评论 {} 条、互动 {} 次、浏览记录 {} 次，耗时 {} ms",
                authors.size(), postPublicIds.size(), comments, reactions, views,
                (System.nanoTime() - startedAt) / 1_000_000);
        log.info("演示账号：{} ~ {}，口令 {}（仅本地演示环境使用）",
                DemoContentLibrary.username(0),
                DemoContentLibrary.username(Math.max(authors.size() - 1, 0)),
                properties.password());
    }

    /**
     * 校验配置可用性。
     *
     * <p>在写任何数据之前一次性检查完：这些错误若放到中途才发现，
     * 留下的就是一份残缺的演示数据，而残缺状态比启动失败更难处理。
     */
    private void requireValidConfiguration() {
        if (properties.password() == null || properties.password().isBlank()) {
            throw new IllegalStateException(
                    "演示数据生成器已启用，但 app.demo-seed.password 为空。"
                            + "演示账号需要一个能通过密码策略的口令，否则注册会失败在生成途中。");
        }
        if (properties.authorCount() <= 0 || properties.postCount() <= 0) {
            throw new IllegalStateException(
                    "演示数据生成器已启用，但 author-count/post-count 必须为正数，当前为 "
                            + properties.authorCount() + " / " + properties.postCount());
        }
    }

    /**
     * 判断是否已经有内容。
     *
     * <p>用列表接口而不是直接查表：这个判断本身就是一次"读一页最新的帖子"，
     * 它走的是对外公开的那条路径，因此不依赖任何内部结构。
     *
     * @return 已存在任意帖子时返回 true
     */
    private boolean hasAnyContent() {
        return postService.list(FeedQuery.of(null, null, null, 1, 1), null).total() > 0;
    }

    /**
     * 确保演示账号存在，并返回它们的内部主键。
     *
     * <p>逐个账号做幂等：已存在则直接复用，不重复注册。这样"账号建好了、
     * 内容还没建"的中间状态重跑时不会撞 {@code 用户名已被使用}。
     *
     * @return 作者内部主键列表
     */
    private List<Long> ensureAuthors() {
        List<Long> authorIds = new ArrayList<>(properties.authorCount());
        int created = 0;
        for (int index = 0; index < properties.authorCount(); index++) {
            String username = DemoContentLibrary.username(index);

            Optional<UserBrief> existing = userDirectory.findBriefByUsername(username);
            if (existing.isPresent()) {
                authorIds.add(existing.get().userId());
                continue;
            }

            authService.register(new AuthService.RegisterCommand(
                    username,
                    DemoContentLibrary.email(index),
                    properties.password(),
                    DemoContentLibrary.nickname(index),
                    DEMO_DEVICE));
            created++;

            // 注册接口按契约不返回内部主键（它不该出现在任何对外响应里），
            // 因此这里按已知的登录名回查。多一次查询换的是"内部标识绝不外流"这条规则不被破坏
            authorIds.add(userDirectory.findBriefByUsername(username)
                    .orElseThrow(() -> new IllegalStateException(
                            "演示账号注册后回查不到：" + username))
                    .userId());
        }
        log.debug("演示数据生成器：新建账号 {} 位，复用 {} 位", created, authorIds.size() - created);
        return authorIds;
    }

    /**
     * 生成帖子。
     *
     * @param authors 作者内部主键列表
     * @return 新帖的对外标识列表（后续用于评论与互动）
     */
    private List<String> createPosts(List<Long> authors) {
        List<String> publicIds = new ArrayList<>(properties.postCount());
        for (int index = 0; index < properties.postCount(); index++) {
            long authorUserId = authors.get(index % authors.size());
            PostCommand command = new PostCommand(
                    DemoContentLibrary.categorySlug(index),
                    DemoContentLibrary.title(index),
                    DemoContentLibrary.body(index),
                    DemoContentLibrary.tags(index));
            publicIds.add(postService.create(authorUserId, command).post().publicId());
        }
        return publicIds;
    }

    /**
     * 生成评论与回复。
     *
     * <p>每帖分配多少条评论由 {@link DemoCommentPlan} 算出，<b>不是</b>"从第一帖开始
     * 顺序填满上限"。后者的后果是评论全落在最旧的一段帖子上，而社区首页默认按发布时间
     * 倒序 —— 也就是说最显眼的位置恰好是一屏零评论，而帖数、评论数、账号登录这些
     * 容易断言的量全都照常正确。
     *
     * <p>帖内结构保持简单：偶数槽位是顶层评论、奇数槽位是回复，因此回复永远挂在同帖的
     * 一条顶层评论下 —— 本平台的讨论结构就是两层，生成器没有理由造出服务层会拒绝的形状。
     *
     * @param postPublicIds 帖子对外标识列表
     * @param authors       作者内部主键列表
     * @return 实际创建的评论条数
     */
    private int createComments(List<String> postPublicIds, List<Long> authors) {
        DemoCommentPlan plan = DemoCommentPlan.distribute(postPublicIds.size(), properties.commentCount());

        int created = 0;
        // 全局评论序号：既用来轮转作者，也用来挑选文案。用递增计数器而不是
        // "帖序号 × 每帖条数 + 槽位"，是因为每帖条数现在是算出来的、逐帖不同。
        int sequence = 0;
        for (int postIndex = 0; postIndex < postPublicIds.size(); postIndex++) {
            String postPublicId = postPublicIds.get(postIndex);
            for (int slot = 0; slot < plan.commentsFor(postIndex); slot++) {
                long authorUserId = authors.get(sequence % authors.size());

                if (slot % 2 == 0) {
                    commentService.create(authorUserId, postPublicId, null,
                            DemoContentLibrary.topLevelComment(sequence));
                } else {
                    // 回复挂在本帖"刚刚那条"顶层评论上。取它靠的是列表接口的一个既有约定：
                    // 顶层评论按创建时间**倒序**返回，因此第一项就是最新的那条。
                    // 这条依赖是刻意写明的 —— 若哪天有人把列表改成正序，
                    // 这里会静默地把回复挂到最旧的评论上，而且不会有任何报错。
                    List<CommentView> topLevel = commentService
                            .listTopLevel(postPublicId, 1, 1).items();
                    if (topLevel.isEmpty()) {
                        // 顶层评论被上限卡掉了。跳过这条回复而不是报错：
                        // 演示数据的规模本来就可以自由调小
                        continue;
                    }
                    commentService.create(authorUserId, postPublicId,
                            topLevel.getFirst().comment().publicId(),
                            DemoContentLibrary.reply(sequence));
                }
                created++;
                sequence++;
            }
        }
        return created;
    }

    /**
     * 生成点赞与收藏。
     *
     * <p>规则刻意写得简单且确定：作者自己不给自己的帖子点赞，其他人按序号取模决定。
     * 这样每一条互动都能被复述出来（"第 7 条帖子被第 3、9 位作者点了赞"），
     * 而不是"当时随机到了这些"。
     *
     * @param postPublicIds 帖子对外标识列表
     * @param authors       作者内部主键列表
     * @return 实际创建的互动条数
     */
    private int createReactions(List<String> postPublicIds, List<Long> authors) {
        int created = 0;
        for (int postIndex = 0; postIndex < postPublicIds.size(); postIndex++) {
            String postPublicId = postPublicIds.get(postIndex);
            long authorUserId = authors.get(postIndex % authors.size());

            for (int authorIndex = 0; authorIndex < authors.size(); authorIndex++) {
                long readerUserId = authors.get(authorIndex);
                if (readerUserId == authorUserId) {
                    continue;
                }
                if ((authorIndex + postIndex) % 3 == 0) {
                    reactionService.like(readerUserId, postPublicId);
                    created++;
                }
                if ((authorIndex + postIndex) % 7 == 0) {
                    reactionService.favorite(readerUserId, postPublicId);
                    created++;
                }
            }
        }
        return created;
    }

    /**
     * 生成浏览记录。
     *
     * <p>走 {@link PostService#detail}：浏览计数只统计登录用户且按天去重，
     * 而"按天去重"这条规则只有走详情接口才会被正确执行 ——
     * 直接改计数列会造出一个刷新就能灌水的数字，与真实口径不一致。
     *
     * @param postPublicIds 帖子对外标识列表
     * @param authors       作者内部主键列表
     * @return 实际记录的浏览次数
     */
    private int createViews(List<String> postPublicIds, List<Long> authors) {
        int recorded = 0;
        for (int postIndex = 0; postIndex < postPublicIds.size(); postIndex++) {
            String postPublicId = postPublicIds.get(postIndex);
            for (int authorIndex = 0; authorIndex < authors.size(); authorIndex++) {
                if ((authorIndex * 5 + postIndex) % 4 != 0) {
                    continue;
                }
                postService.detail(postPublicId, authors.get(authorIndex));
                recorded++;
            }
        }
        return recorded;
    }
}
