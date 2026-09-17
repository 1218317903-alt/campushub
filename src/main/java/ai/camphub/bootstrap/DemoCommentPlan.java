package ai.camphub.bootstrap;

/**
 * 演示评论在帖子之间的分配方案。
 *
 * <h2>为什么需要单独算一次分配</h2>
 * 生成器有两个互相独立的配置：{@code post-count}（帖数）与 {@code comment-count}（评论数上限）。
 * 最直白的实现是"从第一帖开始，逐帖填评论，填满上限就停" —— 但那会产生一个
 * <b>只有读代码才发现得了</b>的后果：评论全部落在<b>最旧</b>的那一段帖子上，
 * 最新的帖子一条评论都没有。
 *
 * <p>而社区首页默认按发布时间<b>倒序</b>排列，于是"演示数据看起来是活的"这件事
 * 恰好被破坏在最显眼的位置：打开首页看到的是一屏 0 评论 0 浏览的帖子。
 * 更糟的是这个后果不影响任何既有断言 —— 帖数、评论数、账号登录全都照常通过。
 *
 * <h2>分配规则</h2>
 * 第 {@code i} 帖分到的评论条数为
 * <pre>
 *     ⌊(i+1)·C / P⌋ − ⌊i·C / P⌋        （P = 帖数，C = 评论上限）
 * </pre>
 * 这条式子有三个正好需要的性质：
 * <ol>
 *   <li><b>总和恰好等于 C</b>（望远镜求和）。因此 C 是严格上限，不会"多生成几条"；</li>
 *   <li><b>任意两帖相差不超过 1 条</b>。分布均匀，不会出现"一帖 200 条、其余 0 条"；</li>
 *   <li><b>C ≥ P 时每帖至少 1 条</b>。没有任何帖子是零评论 —— 这正是本类存在的理由。</li>
 * </ol>
 *
 * <p>当 C 不足以覆盖所有帖子（C &lt; P）时，被选中的帖子是<b>按间隔均匀散开</b>的，
 * 而不是"前 C 帖"。所以哪怕只给 30 条评论、60 个帖子，最新的一页里也会有评论。
 *
 * <p>这个方向上的取舍是明确的：<b>最新的帖子一定有评论，最旧的那一帖不一定</b>。
 * 首帖分配到的条数是 {@code ⌊C/P⌋}，C &lt; P 时就是 0。之所以接受，是因为
 * 首页按发布时间倒序 —— 用户第一眼看的是最新那一端，而最旧的帖子只在深分页里出现。
 * 若哪天首页改为正序，这里就要跟着反过来对齐。
 *
 * <h2>为什么是一个可单测的纯计算</h2>
 * 它没有 IO、没有 Spring 依赖，因此可以用几十毫秒覆盖十几组 (P, C) 组合 ——
 * 而不是靠"启动一次应用、造 2000 条数据、再翻到某一页去看"来发现算错了。
 * 分配逻辑错掉的表现是"看起来正常、只是分布不对"，这类缺陷必须靠断言形状来挡。
 */
final class DemoCommentPlan {

    /** 每帖分配到的评论条数，下标即帖子序号。 */
    private final int[] commentsPerPost;

    private final int total;

    private DemoCommentPlan(int[] commentsPerPost, int total) {
        this.commentsPerPost = commentsPerPost;
        this.total = total;
    }

    /**
     * 按均匀分配规则生成方案。
     *
     * @param postCount    帖子数量
     * @param commentLimit 评论总数上限；为负时按 0 处理
     * @return 分配方案
     */
    static DemoCommentPlan distribute(int postCount, int commentLimit) {
        if (postCount <= 0) {
            return new DemoCommentPlan(new int[0], 0);
        }
        int limit = Math.max(commentLimit, 0);
        int[] counts = new int[postCount];

        // 用 long 做中间量：postCount 与 limit 都在 int 范围内，
        // 但 (index + 1) * limit 会溢出 —— 而这个溢出只在规模调大时才出现，
        // 表现为"某几帖的评论数变成负数"，比直接报错难查得多。
        long previousBoundary = 0;
        for (int index = 0; index < postCount; index++) {
            long boundary = (long) (index + 1) * limit / postCount;
            counts[index] = (int) (boundary - previousBoundary);
            previousBoundary = boundary;
        }
        return new DemoCommentPlan(counts, limit);
    }

    /**
     * @param postIndex 帖子序号（从 0 开始）
     * @return 该帖应生成的评论条数（顶层 + 回复）
     */
    int commentsFor(int postIndex) {
        return commentsPerPost[postIndex];
    }

    /** @return 帖子数量 */
    int postCount() {
        return commentsPerPost.length;
    }

    /** @return 计划生成的评论总数，等于构造时给定的上限（帖数为 0 时为 0） */
    int total() {
        return total;
    }

    /** @return 至少分配到一条评论的帖子数量 */
    int coveredPostCount() {
        int covered = 0;
        for (int count : commentsPerPost) {
            if (count > 0) {
                covered++;
            }
        }
        return covered;
    }
}
