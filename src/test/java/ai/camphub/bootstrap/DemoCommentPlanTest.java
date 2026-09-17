package ai.camphub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DemoCommentPlan} 的分配规则测试。
 *
 * <h2>为什么这些断言值钱</h2>
 * 分配算错的表现不是抛异常，而是"跑得通、数也对、只是分布不对" ——
 * 唯一的发现途径是有人正好翻到某一页去看。而它的代价恰恰落在最显眼的地方：
 * 社区首页按时间倒序，评论若全在最旧的一段，首页就是一屏零评论。
 *
 * <p>因此这里断言的是<b>形状</b>：总和恰好等于上限、任意两帖相差不超过 1、
 * 最新的帖子也在覆盖范围内。这些都是"不看数据也能判定"的性质。
 */
class DemoCommentPlanTest {

    /**
     * 真实使用到的规模组合。默认演示规模与基线测量规模都在里面 ——
     * 前者保证改动没有改变默认外观，后者保证大规模数据也是均匀的。
     */
    private static List<Object[]> sizes() {
        List<Object[]> sizes = new ArrayList<>();
        sizes.add(new Object[] {60, 240, "默认演示规模：12 作者 / 60 帖 / 240 评论"});
        sizes.add(new Object[] {12, 20, "集成测试规模：评论不足以覆盖每帖 4 条"});
        sizes.add(new Object[] {2000, 4000, "基线测量规模：2000 帖 / 4000 评论"});
        sizes.add(new Object[] {2000, 500, "评论远少于帖数"});
        sizes.add(new Object[] {7, 100, "少量帖子、大量评论"});
        sizes.add(new Object[] {1, 1, "单帖单评论"});
        return sizes;
    }

    @Test
    @DisplayName("评论总数严格等于配置的上限，不多也不少")
    void totalShouldEqualLimitExactly() {
        for (Object[] size : sizes()) {
            int postCount = (int) size[0];
            int limit = (int) size[1];

            DemoCommentPlan plan = DemoCommentPlan.distribute(postCount, limit);

            int sum = 0;
            for (int index = 0; index < plan.postCount(); index++) {
                sum += plan.commentsFor(index);
            }
            assertThat(sum).as("%s：分配总数应严格等于上限", size[2]).isEqualTo(limit);
            assertThat(plan.total()).as("%s：plan.total()", size[2]).isEqualTo(limit);
        }
    }

    @Test
    @DisplayName("分布均匀：任意两帖的评论数相差不超过 1")
    void distributionShouldBeEven() {
        for (Object[] size : sizes()) {
            int postCount = (int) size[0];
            DemoCommentPlan plan = DemoCommentPlan.distribute(postCount, (int) size[1]);

            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int index = 0; index < plan.postCount(); index++) {
                min = Math.min(min, plan.commentsFor(index));
                max = Math.max(max, plan.commentsFor(index));
            }
            assertThat(max - min).as("%s：最多与最少之差应不超过 1", size[2]).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("评论数不少于帖数时，每一帖都有评论 —— 没有任何帖子是零评论")
    void everyPostShouldGetAtLeastOneCommentWhenBudgetAllows() {
        DemoCommentPlan plan = DemoCommentPlan.distribute(2000, 4000);

        assertThat(plan.coveredPostCount())
                .as("首页按时间倒序，任何一段帖子没有评论都会让那一页看起来像死社区")
                .isEqualTo(2000);
    }

    @Test
    @DisplayName("评论数少于帖数时也要散开：最新那帖有评论，且被覆盖的帖子均匀间隔")
    void latestPostShouldStillBeCoveredWhenBudgetIsTight() {
        int postCount = 2000;
        int limit = 500;
        DemoCommentPlan plan = DemoCommentPlan.distribute(postCount, limit);

        assertThat(plan.coveredPostCount()).isEqualTo(limit);
        assertThat(plan.commentsFor(postCount - 1))
                .as("最新那条帖子必须有评论：社区首页第一屏看到的就是它")
                .isEqualTo(1);

        // 下面这条才是能区分"散开"与"堆在最前面"的断言：
        // 旧实现（从第一帖起逐帖填满就停）在这里会得到 499 —— 它的评论全在最旧的 500 帖里，
        // 而数量、总数、均匀性三项全都正常。
        int lastCoveredIndex = -1;
        for (int index = 0; index < postCount; index++) {
            if (plan.commentsFor(index) > 0) {
                lastCoveredIndex = index;
            }
        }
        assertThat(lastCoveredIndex)
                .as("被覆盖的帖子要一直延伸到最新的那一端，而不是断在最旧的某处")
                .isEqualTo(postCount - 1);

        // 间隔也是有意义的形状保证：每 ceil(P/C) 帖里至少有一条评论，
        // 也就是"随便翻一页都能看到讨论"，而不是"只有开头几页有"。
        int maxGap = 0;
        int previousCovered = -1;
        for (int index = 0; index < postCount; index++) {
            if (plan.commentsFor(index) > 0) {
                if (previousCovered >= 0) {
                    maxGap = Math.max(maxGap, index - previousCovered);
                }
                previousCovered = index;
            }
        }
        assertThat(maxGap)
                .as("相邻两条评论之间的帖子间隔")
                .isLessThanOrEqualTo((int) Math.ceil((double) postCount / limit));
    }

    @Test
    @DisplayName("默认演示规模下每帖 4 条：改动没有改变默认外观")
    void defaultScaleShouldKeepFourPerPost() {
        DemoCommentPlan plan = DemoCommentPlan.distribute(60, 240);

        assertThat(plan.commentsFor(0)).isEqualTo(4);
        assertThat(plan.commentsFor(59)).isEqualTo(4);
        assertThat(plan.coveredPostCount()).isEqualTo(60);
    }

    @Test
    @DisplayName("规模调大时每帖均匀减少，而不是把后面的帖子空出来")
    void largerScaleShouldReducePerPostInsteadOfLeavingPostsEmpty() {
        DemoCommentPlan plan = DemoCommentPlan.distribute(2000, 4000);

        assertThat(plan.commentsFor(0)).isEqualTo(2);
        assertThat(plan.commentsFor(1999)).isEqualTo(2);
    }

    @Test
    @DisplayName("边界：帖数为 0、评论上限为 0 或负数都不崩，且不产生任何评论")
    void degenerateInputsShouldBeSafe() {
        assertThat(DemoCommentPlan.distribute(0, 100).postCount()).isZero();
        assertThat(DemoCommentPlan.distribute(0, 100).total()).isZero();

        assertThat(DemoCommentPlan.distribute(10, 0).coveredPostCount()).isZero();
        assertThat(DemoCommentPlan.distribute(10, 0).total()).isZero();

        // 负数按 0 处理，而不是分配出负条数 —— 那会让调用方的循环直接不执行，
        // 表现为"评论一条都没生成"，而日志里的上限还写着负数
        assertThat(DemoCommentPlan.distribute(10, -5).total()).isZero();
    }

    @Test
    @DisplayName("大规模时不发生整型溢出：条数不会变成负数")
    void largeScaleShouldNotOverflow() {
        DemoCommentPlan plan = DemoCommentPlan.distribute(200_000, 2_000_000);

        int sum = 0;
        for (int index = 0; index < plan.postCount(); index++) {
            int count = plan.commentsFor(index);
            assertThat(count).as("第 %d 帖", index).isNotNegative();
            sum += count;
        }
        assertThat(sum).isEqualTo(2_000_000);
    }
}
