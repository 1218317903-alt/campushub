package ai.camphub.bootstrap;

import java.util.List;

/**
 * 合成演示内容的语料与组合规则。
 *
 * <h2>这一层只做一件事：把"索引"确定性地映射成内容</h2>
 * 同一个索引永远得到同一份内容，没有随机数参与。原因不是"可复现更优雅"，
 * 而是演示数据必须能被讨论 —— 当有人指着首页某条帖子说"这条怎么这么写"，
 * 你需要能从上到下讲出它是怎么被生成出来的；一个靠随机数拼出来的数据集做不到这一点。
 *
 * <p>因此这里只有纯函数：给定索引与类别，返回确定的标题、正文、标签、评论。
 * 不依赖 Spring、不依赖数据库、不依赖时间 —— 它可以在一个普通单元测试里被完整读一遍。
 *
 * <h2>为什么正文由"段落池 + 组合规则"拼出，而不是逐条手写</h2>
 * 60 条帖子的手写正文会立刻退化成复制粘贴，反而更快变得不真实；
 * 而"从池子里取几段、按类别插入一段代码或表格"既保证了体裁上的差异
 * （技术帖有代码、笔记有表格、问答有复现步骤），又让语料总量可控 ——
 * 要扩充内容只需往池子里加一段，而不是再加 60 条。
 */
final class DemoContentLibrary {

    private DemoContentLibrary() {
    }

    /**
     * 演示作者的昵称池。
     *
     * <p>用真实感的中文名而不是 {@code user1 / user2}：演示数据的价值就在于
     * "看起来像真实使用过的系统"，而 {@code user3} 这种昵称会让任何人在半秒内
     * 判定这是假数据，也让人无法判断界面在真实长度下的排版是否成立。
     */
    private static final String[] NICKNAMES = {
            "陈知远", "林一鸣", "苏晚晴", "周予安", "许清和", "郑斯年",
            "顾亭之", "叶嘉树", "何砚秋", "唐立诚", "徐怀瑾", "孟星野",
            "罗闻笛", "程之澜", "沈砚青", "白露微"
    };

    /** 帖子的开场句。用池子而不是固定一句，避免 60 条帖子以完全相同的一句话开头。 */
    private static final String[] OPENINGS = {
            "这周在这件事上花了不少时间，把过程和结论整理一下，希望对遇到同样问题的同学有用。",
            "先说结论，再讲过程。中间绕的弯路我都标出来了，可以跳过。",
            "整理这份东西的起因是自己踩了一次坑，翻了不少资料才发现原因很简单。",
            "以下内容基于我这学期的实际经历，不同学校、不同课程的情况可能有差异。",
            "趁着记得还清楚，先把关键的部分记下来。"
    };

    /** 帖子的收尾句。 */
    private static final String[] CLOSINGS = {
            "以上是我这边的经验，如果有更简单的做法，欢迎在评论区指出。",
            "写得比较细，希望能少让一个人走弯路。有问题可以在下面问。",
            "先记到这里，后面有新的发现再来补充。",
            "如果对你有帮助，点个收藏就好，不用特意说谢谢。",
            "以上，供参考。"
    };

    // ----------------------------------------------------------------------
    // 各板块的语料
    // ----------------------------------------------------------------------

    /**
     * 一个板块的语料。
     *
     * @param slug         板块对外标识，与 V3 迁移里的种子一致
     * @param topics       主题列表，用于生成标题
     * @param titlePatterns 标题模板，{@code {topic}} 会被替换成主题
     * @param tags         该板块常用的标签（必须全部是 V3 种子里的 slug）
     * @param paragraphs   正文段落池
     * @param extras       正文里额外插入的结构化片段（代码块 / 表格 / 列表），可为空
     */
    private record Vocabulary(
            String slug,
            String[] topics,
            String[] titlePatterns,
            String[] tags,
            String[] paragraphs,
            String[] extras) {
    }

    private static final Vocabulary[] VOCABULARIES = {
            new Vocabulary(
                    "tech",
                    new String[]{
                            "Spring Boot 启动变慢", "MySQL 索引失效", "Vue 3 的响应式依赖收集",
                            "JVM 内存占用偏高", "Git 分支策略", "缓存穿透",
                            "单元测试与集成测试的边界", "本地开发环境容器化"
                    },
                    new String[]{
                            "{topic}：我踩过的坑与结论", "记录一次{topic}的排查过程",
                            "{topic}的一点实践经验", "关于{topic}，我的理解是这样的"
                    },
                    new String[]{"java", "spring-boot", "mysql", "frontend", "open-source", "algorithm"},
                    new String[]{
                            "问题出现得很突然：本地跑得好好的，部署上去就是慢一截。第一反应是网络，但抓包看下来请求量并不大。",
                            "关键在于先把现象量化。没有数字的排查只能靠猜，而靠猜的排查通常会改一堆无关的东西，最后连是哪一步修好的都说不清楚。",
                            "翻源码之后发现，真正的原因往往和最初怀疑的方向无关。这一步很花时间，但省不掉 —— 别人的结论和自己的场景总有出入。",
                            "修完之后回头看，其实有两处可以更早发现：一个是监控里早就有的指标，一个是文档里明确写了的默认行为，当时都没细看。",
                            "顺手把这个过程写成脚本，以后环境重装可以直接跑一遍，不用再靠记忆复现。",
                            "最后提醒一句：这类问题的结论和版本强相关，抄结论之前先确认版本号，否则会把别人的修法用在不适用的地方。"
                    },
                    new String[]{
                            "```java\n// 关键是显式指定，而不是依赖默认值\nDataSource ds = DataSourceBuilder.create()\n        .url(\"jdbc:mysql://localhost:3306/app\")\n        .build();\n```",
                            "| 方案 | 改动量 | 效果 | 代价 |\n| --- | --- | --- | --- |\n| 加缓存 | 小 | 明显 | 多一处一致性风险 |\n| 改索引 | 小 | 明显 | 需要回填 |\n| 拆服务 | 大 | 不确定 | 运维复杂度上升 |",
                            "- 先确认版本号\n- 再确认默认值\n- 最后才是改代码"
                    }),
            new Vocabulary(
                    "study-notes",
                    new String[]{
                            "进程调度", "TCP 三次握手", "动态规划", "特征值与特征向量",
                            "红黑树", "词法分析", "网络分层模型", "贝叶斯公式"
                    },
                    new String[]{
                            "{topic} 笔记整理", "{topic}：一页纸复习提纲",
                            "期末复习 · {topic}", "{topic}的知识框架与易错点"
                    },
                    new String[]{"algorithm", "operating-system", "computer-network", "english", "exam-review"},
                    new String[]{
                            "复习的时候发现，这一块的教材顺序和理解的顺序并不一致。按理解的顺序重新排一遍，记起来顺得多。",
                            "先给一张整体图，再补细节。直接进细节的话，容易在第二步就迷路，然后靠背，背完就忘。",
                            "最容易考也最容易错的是边界情况。老师上课时特意强调过，但当时没在意，做题时才明白为什么。",
                            "把易错点单独列出来，考前只看这一页。这是我的做法，不一定适合所有人，但至少不用每次从头翻。",
                            "配套的例题我挑了五道，都是同一个知识点的不同问法。同一知识点换问法就认不出来，说明还没真正理解。",
                            "补充一点：不同教材的符号约定不一样，看笔记时先确认符号，否则会把两个相反的结论记成同一个。"
                    },
                    new String[]{
                            "| 概念 | 一句话说明 | 常见问法 |\n| --- | --- | --- |\n| 就绪 | 可运行但没拿到 CPU | 队列长度怎么算 |\n| 阻塞 | 在等外部事件 | 与就绪的区别 |",
                            "```\n状态转移：\n  新建 → 就绪 → 运行 → 终止\n           ↑      ↓\n           └── 阻塞\n```",
                            "1. 先看整体流程\n2. 再抠每一步的不变量\n3. 最后做边界题"
                    }),
            new Vocabulary(
                    "campus",
                    new String[]{
                            "图书馆自习座位", "选课系统", "宿舍作息", "社团招新",
                            "食堂窗口", "体测", "校园卡补办", "奖学金评定"
                    },
                    new String[]{
                            "关于{topic}，分享一点经验", "{topic}这件事，想跟新生聊聊",
                            "{topic}的现状与几点建议", "记录一下{topic}"
                    },
                    new String[]{"exam-review", "scholarship", "contest", "english"},
                    new String[]{
                            "每到这个时间段，这件事就会成为大家讨论最多的话题。今年我自己完整经历了一遍，把实际情况写下来。",
                            "网上的说法大多过期了，制度去年调整过一次，按老帖子里的流程走会白跑一趟。",
                            "流程本身不复杂，麻烦的是信息不集中：通知在群里、表格在另一个系统、要求写在附件里。",
                            "时间点很重要。卡在截止日当天处理，出任何一点意外都没有补救余地。提前一周比较稳妥。",
                            "遇到不确定的地方，直接去对应窗口问比在群里问快得多 —— 群里给的多半是别人的理解。",
                            "整体感受是，只要材料齐、时间留够，就不会有问题。把这份记录放这里，供后面的人参考。"
                    },
                    new String[]{
                            "- 提前确认办理时间，避开午休\n- 带上学生证与身份证\n- 材料复印两份备用",
                            "| 时间 | 事项 | 地点 |\n| --- | --- | --- |\n| 第 1 周 | 提交申请 | 行政楼 3 层 |\n| 第 2 周 | 材料复核 | 线上 |",
                            null
                    }),
            new Vocabulary(
                    "qa",
                    new String[]{
                            "构造器循环引用", "前端跨域", "连接池耗尽", "Markdown 渲染被注入",
                            "深分页变慢", "时区导致时间错乱", "令牌过期后刷新", "大文件上传中断"
                    },
                    new String[]{
                            "求助：{topic}怎么解决", "{topic}，有同学遇到过吗",
                            "关于{topic}的一个疑问", "{topic}排查两天了，还是没头绪"
                    },
                    new String[]{"java", "mysql", "algorithm", "frontend", "operating-system", "computer-network"},
                    new String[]{
                            "环境与版本都写在下面了，能复现，不是偶发。已经排除的项也列了出来，省得大家重复问。",
                            "现象是：正常路径没问题，一旦走到某个分支就失败。日志里的异常信息我贴了完整的一段。",
                            "怀疑过三个方向，逐个验证下来都不是。现在怀疑是自己的理解有偏差，但不确定偏在哪。",
                            "复现步骤我尽量写细了：从干净的环境开始，按顺序执行就能出现。这样大家不用猜我的操作习惯。",
                            "有一个细节可能是关键：同样的输入，第二次执行结果和第一次不同。这一点我想不明白。",
                            "如果哪位遇到过类似的，麻烦说一下当时的根因。就算方向不对，能排除一个方向也是有帮助的。"
                    },
                    new String[]{
                            "```\n异常信息：\nCaused by: java.lang.IllegalStateException: \n    Requested bean is currently in creation\n```",
                            "- 已排除：版本不匹配\n- 已排除：配置项写错\n- 待验证：初始化顺序",
                            null
                    }),
            new Vocabulary(
                    "resources",
                    new String[]{
                            "考研数学真题", "算法题单", "四六级词汇表", "开源项目脚手架",
                            "课程课件", "算法可视化工具", "论文写作模板", "文献管理工具"
                    },
                    new String[]{
                            "分享：{topic}", "{topic}整理，需要的同学自取",
                            "{topic}合集（持续更新）", "自己整理了一份{topic}"
                    },
                    new String[]{"open-source", "exam-review", "english", "algorithm", "contest", "postgraduate-exam"},
                    new String[]{
                            "整理这份东西断断续续花了挺久，主要是做去重和校对 —— 网上流传的版本里错漏不少。",
                            "目录按使用频率排，不是按章节顺序。真用起来的时候，翻得最多的是后面那几节。",
                            "所有内容都标注了来源和年份，方便核对。有疑问的地方我标了问号，没有自己补全。",
                            "格式统一成了 PDF 与 Markdown 两份：前者方便打印，后者方便自己改。",
                            "后续如果有更新，我会在这里追加，不另开新帖。有补充的同学也欢迎在评论区贴出来。",
                            "说明一下使用范围：仅供校内学习交流，请勿用于任何商业用途。"
                    },
                    new String[]{
                            "| 目录 | 内容 | 更新日期 |\n| --- | --- | --- |\n| 一 | 基础部分 | 2026-03 |\n| 二 | 真题 | 2026-06 |",
                            "- 打印版：A4 双面，共 48 页\n- 电子版：Markdown，便于自己改\n- 来源：均在文末标注",
                            null
                    }),
            new Vocabulary(
                    "career",
                    new String[]{
                            "程序设计竞赛", "数学建模", "挑战杯", "开源之夏",
                            "实验室项目", "项目复盘", "团队协作", "技术分享会"
                    },
                    new String[]{
                            "{topic}参赛记录", "{topic}：从组队到提交",
                            "关于{topic}的一些体会", "{topic}复盘"
                    },
                    new String[]{"internship", "contest", "open-source"},
                    new String[]{
                            "从决定参加到提交，前后不到两个月。回头看，时间分配上有很多可以改进的地方。",
                            "组队是最关键的一步，比选题还关键。能力互补比每个人都强更重要，这点我们吃了亏。",
                            "过程中最大的问题是信息不同步：各自看各自的资料，等到对进度才发现方向已经分叉了。",
                            "技术上的难点最后反而是最容易解决的，真正难的是「这件事要不要做」这类判断。",
                            "提交前一晚发现一处硬伤，改完已经是凌晨。教训是留白要比想象中多一倍。",
                            "成绩是一方面，但对我影响更大的是完整走了一遍从选题到交付的流程。这份记录留给自己，也留给后面的人。"
                    },
                    new String[]{
                            "| 阶段 | 时长 | 主要产出 |\n| --- | --- | --- |\n| 选题 | 1 周 | 方向与分工 |\n| 开发 | 4 周 | 可演示版本 |\n| 打磨 | 2 周 | 文档与演示 |",
                            "- 每周固定一次同步，只讲进展与阻塞\n- 所有决定写进文档，不留在聊天记录里",
                            null
                    })
    };

    /** 顶层评论池。 */
    private static final String[] TOP_LEVEL_COMMENTS = {
            "写得很清楚，尤其是把结论放在最前面这一点。收藏了。",
            "我们这边的情况正好相反，可能是版本不同。我回去确认一下再说。",
            "第二步那里，如果前一个条件不成立会怎样？我按同样的步骤走，卡在了这一步。",
            "补充一点：这个方法在数据量小的时候没问题，量级上去了要注意一下。",
            "感谢整理。之前找了好久，终于有一份能直接照着做的。",
            "有个疑问：文中说的默认值，是哪个版本之后才改的？",
            "按这个思路试了一遍，问题解决了。原因是配置里少写了一项。",
            "这个坑我也踩过，当时查了两天才定位到。早知道这里有一份就省事了。",
            "写得挺好，不过第三点我有不同看法，回去整理一下再回。",
            "刚好在做相关的事情，这份内容来得正是时候。",
            "同一个问题困扰了我一周，谢谢。",
            "细节很到位，连容易忽略的边界情况都写了。"
    };

    /** 回复池。 */
    private static final String[] REPLIES = {
            "同问，我也卡在这里。",
            "先把版本号贴出来，答案和版本关系很大。",
            "我这边的情况是配置没生效，检查一下有没有被后面的配置覆盖。",
            "试了一下，确实是这样。谢谢。",
            "补充一个更省事的做法：直接看日志里输出的实际生效值。",
            "不是这个问题，我按另一个思路解决了，回头写一下。",
            "楼上说得对，我漏看了这一条。",
            "这个结论我验证过了，可以放心用。",
            "有没有可能和系统时区有关？我之前遇到过类似的现象。",
            "文档里其实提到了，只是藏在附注里，很容易漏。",
            "我也遇到过，最后发现是缓存没清。",
            "先收藏，周末试一下再回来反馈。"
    };

    // ----------------------------------------------------------------------
    // 对外接口：索引 → 内容
    // ----------------------------------------------------------------------

    /**
     * 取指定序号的演示账号昵称。
     *
     * @param index 序号（从 0 开始）
     * @return 昵称
     */
    static String nickname(int index) {
        String base = NICKNAMES[Math.floorMod(index, NICKNAMES.length)];
        int round = Math.floorDiv(index, NICKNAMES.length);
        // 第二轮起加序号：昵称不是唯一键，但两个演示账号叫同一个名字会让
        // "谁发的这条帖子"在人工核对时变得无法判断
        return round == 0 ? base : base + (round + 1);
    }

    /**
     * 取指定序号的演示账号登录名。
     *
     * @param index 序号（从 0 开始）
     * @return 登录名
     */
    static String username(int index) {
        return "demo%02d".formatted(index + 1);
    }

    /**
     * 取指定序号的演示账号邮箱。
     *
     * @param index 序号（从 0 开始）
     * @return 邮箱
     */
    static String email(int index) {
        return username(index) + "@camphub.local";
    }

    /**
     * 生成帖子的板块。
     *
     * <p>按序号轮转而不是随机：轮转保证每个板块都有内容。随机分配在 60 条这个量级上
     * 经常出现某个板块只有一条帖子，演示时一进那个板块就是空页面 ——
     * 而"每个板块都有人发言"恰恰是演示数据要传达的信息。
     *
     * @param index 序号（从 0 开始）
     * @return 板块 slug
     */
    static String categorySlug(int index) {
        return VOCABULARIES[Math.floorMod(index, VOCABULARIES.length)].slug();
    }

    /**
     * 生成帖子标题。
     *
     * @param index 序号（从 0 开始）
     * @return 标题
     */
    static String title(int index) {
        Vocabulary vocabulary = vocabularyFor(index);
        String topic = vocabulary.topics()[Math.floorMod(index, vocabulary.topics().length)];
        String pattern = vocabulary.titlePatterns()[
                Math.floorDiv(index, vocabulary.topics().length) % vocabulary.titlePatterns().length];
        return pattern.replace("{topic}", topic);
    }

    /**
     * 生成帖子正文（Markdown）。
     *
     * @param index 序号（从 0 开始）
     * @return Markdown 正文
     */
    static String body(int index) {
        Vocabulary vocabulary = vocabularyFor(index);
        StringBuilder body = new StringBuilder();

        body.append(OPENINGS[Math.floorMod(index, OPENINGS.length)]).append("\n\n");

        String[] paragraphs = vocabulary.paragraphs();
        int start = Math.floorMod(index * 3, paragraphs.length);
        for (int offset = 0; offset < 3; offset++) {
            body.append(paragraphs[(start + offset) % paragraphs.length]).append("\n\n");

            // 结构化片段插在第一段之后：放开头会喧宾夺主，放末尾又常被跳过
            String[] extras = vocabulary.extras();
            if (offset == 0 && extras.length > 0) {
                String extra = extras[Math.floorMod(index, extras.length)];
                if (extra != null) {
                    body.append(extra).append("\n\n");
                }
            }
        }

        // 技术帖与问答帖以代码结尾，看起来更像真实的技术讨论；
        // 注意这里也只从池子里取，不让语料随类别而"发明"新片段
        if (vocabulary.slug().equals("tech") && extrasOf(vocabulary).length > 1) {
            body.append(extrasOf(vocabulary)[1]).append("\n\n");
        }

        body.append(CLOSINGS[Math.floorMod(index, CLOSINGS.length)]);
        return body.toString();
    }

    /**
     * 生成帖子标签。
     *
     * @param index 序号（从 0 开始）
     * @return 标签名列表，数量在 2~3 之间
     */
    static List<String> tags(int index) {
        String[] pool = vocabularyFor(index).tags();
        int count = 2 + Math.floorMod(index, 2);
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(offset -> pool[(Math.floorMod(index, pool.length) + offset) % pool.length])
                .distinct()
                .toList();
    }

    /**
     * 取顶层评论文本。
     *
     * @param index 序号
     * @return 评论正文
     */
    static String topLevelComment(int index) {
        return TOP_LEVEL_COMMENTS[Math.floorMod(index, TOP_LEVEL_COMMENTS.length)];
    }

    /**
     * 取回复文本。
     *
     * @param index 序号
     * @return 回复正文
     */
    static String reply(int index) {
        return REPLIES[Math.floorMod(index, REPLIES.length)];
    }

    /**
     * 按序号取板块语料。
     *
     * @param index 序号（从 0 开始）
     * @return 语料
     */
    private static Vocabulary vocabularyFor(int index) {
        return VOCABULARIES[Math.floorMod(index, VOCABULARIES.length)];
    }

    /**
     * 取语料的结构化片段数组。
     *
     * @param vocabulary 语料
     * @return 片段数组
     */
    private static String[] extrasOf(Vocabulary vocabulary) {
        return vocabulary.extras();
    }
}
