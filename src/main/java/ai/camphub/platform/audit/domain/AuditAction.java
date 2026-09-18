package ai.camphub.platform.audit.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审计动作码。
 *
 * <h2>为什么用枚举而不是随手写字符串</h2>
 * 审计日志的价值取决于<b>可聚合性</b>：出了事要能问"最近 24 小时有多少次
 * {@code AUTH_LOGIN_FAILURE}"。如果动作码是自由字符串，早晚会同时出现
 * {@code AUTH_LOGIN_FAILURE} / {@code LOGIN_FAILED} / {@code auth.login.fail} 三种写法，
 * 聚合查询就废了。枚举把这个约束交给编译器。
 *
 * <h2>范围纪律</h2>
 * 本枚举只登记<b>已经在写</b>的动作。不为尚未实现的阶段预留动作码 ——
 * 一个永远不会有日志写入的动作码，只会让"到底哪些操作被审计了"这个问题更难回答。
 * 后续阶段各自追加（Phase 03 加内容类，Phase 10 加审核与后台类）。
 */
public enum AuditAction {

    /** 注册成功。 */
    AUTH_REGISTER("注册账号"),

    /** 登录成功。 */
    AUTH_LOGIN_SUCCESS("登录成功"),

    /** 登录失败（口令错误、账号不存在、账号不可用等，具体原因记在 detail 里）。 */
    AUTH_LOGIN_FAILURE("登录失败"),

    /** 主动登出当前会话。 */
    AUTH_LOGOUT("登出"),

    /** 登出全部设备（递增令牌世代号）。 */
    AUTH_LOGOUT_ALL("登出全部设备"),

    /** 用刷新令牌换取新的令牌对。 */
    AUTH_TOKEN_REFRESH("刷新令牌"),

    /**
     * 检测到刷新令牌重放：一个已被轮换失效的令牌被再次使用。
     *
     * <p>这是<b>安全事件</b>而非普通失败 —— 它意味着令牌已经泄露到客户端之外，
     * 因此处置动作是撤销该用户全部会话，而不是只拒绝这一次请求。
     */
    AUTH_TOKEN_REPLAY_DETECTED("刷新令牌重放（判定为泄露）"),

    /** 修改密码（同时递增令牌世代号，使其它设备的登录状态失效）。 */
    AUTH_PASSWORD_CHANGE("修改密码"),

    /** 修改个人资料。 */
    USER_PROFILE_UPDATE("修改个人资料"),

    /** 单独下线某个设备会话。 */
    SESSION_REVOKE("下线设备会话"),

    // ---------------------------------------------------------------------
    // 内容类（Phase 03 起）
    // ---------------------------------------------------------------------
    // 内容的**创建**也记审计，而不是只记删除：审计在这里的用途不是"追责用户"，
    // 而是回答"这条内容是什么时候、由谁、通过哪次请求产生的"。
    // 内容纠纷与垃圾内容排查都从这个问题开始，而仅凭业务表里的 created_at 与 author_id
    // 无法区分"用户自己发的"与"某个接口被滥用代发的"。
    // ---------------------------------------------------------------------

    /** 发布帖子。 */
    CONTENT_POST_CREATE("发布帖子"),

    /** 编辑帖子。 */
    CONTENT_POST_UPDATE("编辑帖子"),

    /** 删除帖子（作者本人操作，软删除）。 */
    CONTENT_POST_DELETE("删除帖子"),

    /** 删除评论或回复（作者本人操作，软删除）。 */
    CONTENT_COMMENT_DELETE("删除评论"),

    // ---------------------------------------------------------------------
    // 空间类（Phase 04 起）
    // ---------------------------------------------------------------------
    // 私有内容的审计比公开内容更重要，因为出问题时没有第三方能作证：
    // 一条帖子的争议可以靠公开页面还原，而"谁把谁移出了空间""谁在什么时候
    // 读了哪份文档"在其他任何地方都没有痕迹。
    //
    // **刻意不为"读取空间列表""读取成员列表"这类批量只读操作记审计**：
    // 它们每次翻页都会产生一行，几百个用户就能把审计表撑成一份访问日志，
    // 而审计的价值在于"少而关键"。真正需要留痕的读取只有一种 ——
    // 下面那条 DOCUMENT_DOWNLOAD：它取出的是文件原内容，一旦离开系统就无法收回。
    // ---------------------------------------------------------------------

    /** 创建空间。 */
    WORKSPACE_CREATE("创建空间"),

    /** 修改空间设置（名称、描述、可见性）。 */
    WORKSPACE_UPDATE("修改空间设置"),

    /** 删除空间（拥有者操作，软删除）。 */
    WORKSPACE_DELETE("删除空间"),

    /** 邀请他人加入空间。 */
    WORKSPACE_MEMBER_INVITE("邀请成员"),

    /** 兑换邀请码加入空间。被邀请人自己的动作，与上面那条分属双方视角。 */
    WORKSPACE_MEMBER_JOIN("加入空间"),

    /** 撤销一条尚未被使用的邀请。 */
    WORKSPACE_INVITE_REVOKE("撤销邀请"),

    /** 移除空间成员。 */
    WORKSPACE_MEMBER_REMOVE("移除成员"),

    /**
     * 自己退出空间。
     *
     * <p>与上面的"被移除"分开记录，而不是用同一条码加一个 {@code self} 标记：
     * 两者在事后排查里的意义完全不同 —— 「他自己走的」与「他被移出了」
     * 对"这个空间发生了什么"给出的答案是相反的。把它们合成一条码之后，
     * 想区分就只能靠读审计明细里的字段，而那种约定不会有人记得。
     */
    WORKSPACE_MEMBER_LEAVE("退出空间"),

    /** 修改成员在空间内的角色。 */
    WORKSPACE_MEMBER_ROLE_UPDATE("修改成员角色"),

    /** 创建笔记。 */
    NOTE_CREATE("创建笔记"),

    /** 编辑笔记。协作内容，因此要记下是谁改的。 */
    NOTE_UPDATE("编辑笔记"),

    /** 删除笔记（软删除）。 */
    NOTE_DELETE("删除笔记"),

    /**
     * 上传文档。
     *
     * <p>与帖子的创建记审计是同一条理由：回答"这份文件是什么时候、由谁、
     * 通过哪次请求进入系统的"。空间文档没有公开页面可以还原，
     * 这条记录往往是唯一的来源。
     */
    DOCUMENT_UPLOAD("上传文档"),

    /**
     * 下载文档原文件。
     *
     * <p>这是本阶段唯一被审计的读取动作。理由是它的性质与列表查询不同：
     * 列表只暴露元数据，而下载把文件内容交到了请求方手上。
     * 数据外带类事件的事后排查只能从这条记录开始。
     */
    DOCUMENT_DOWNLOAD("下载文档"),

    /** 删除文档（软删除）。 */
    DOCUMENT_DELETE("删除文档");

    /** 动作码 → 中文说明，供后台展示与文档生成复用。 */
    private static final Map<String, AuditAction> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    private final String description;

    AuditAction(String description) {
        this.description = description;
    }

    /**
     * @return 面向人的中文说明
     */
    public String description() {
        return description;
    }

    /**
     * 按名称查找动作码。
     *
     * @param name 动作码名称
     * @return 对应的动作码
     * @throws IllegalArgumentException 名称未登记时
     */
    public static AuditAction of(String name) {
        AuditAction action = BY_NAME.get(name);
        if (action == null) {
            throw new IllegalArgumentException("未登记的审计动作码：" + name);
        }
        return action;
    }
}
