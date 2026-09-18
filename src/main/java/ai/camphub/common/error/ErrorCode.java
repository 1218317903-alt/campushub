package ai.camphub.common.error;

import org.springframework.http.HttpStatus;

/**
 * 全局业务错误码。
 *
 * <h2>编码规则（5 位数字）</h2>
 * <pre>
 *   HHH SS
 *   │   └─ 同一 HTTP 状态内的序号（00 起）
 *   └───── HTTP 状态码，便于一眼看出该错误对应的传输语义
 * </pre>
 * 例：{@code 40400} = HTTP 404 的第 0 号错误。
 *
 * <h2>序号分配约定（防止各模块撞号）</h2>
 * <table border="1">
 *   <caption>序号段</caption>
 *   <tr><td>40000 ~ 40009</td><td>通用请求错误（参数、校验、类型）</td></tr>
 *   <tr><td>40010 ~ 40019</td><td>identity 模块（Phase 02 起）</td></tr>
 *   <tr><td>40020 ~ 40029</td><td>workspace 模块（Phase 04 起）</td></tr>
 *   <tr><td>40030 ~ 40039</td><td>community 模块（Phase 03 起）</td></tr>
 *   <tr><td>40040 ~ 40049</td><td>ingestion / discover 模块（Phase 06 起）</td></tr>
 *   <tr><td>40050 ~ 40059</td><td>ai 模块（Phase 08 起）</td></tr>
 *   <tr><td>40100 ~ 40199</td><td>认证失败（未登录 / 令牌问题 / 凭据错误）</td></tr>
 *   <tr><td>40300 ~ 40399</td><td>已认证但被拒绝（权限不足 / 账号不可用）</td></tr>
 *   <tr><td>41300 ~ 41399</td><td>请求体过大（分片上传超出容器上限）</td></tr>
 *   <tr><td>42900 ~ 42999</td><td>限流</td></tr>
 * </table>
 * 新增错误码时先在本文档登记，再使用。
 */
public enum ErrorCode {

    // ---------- 400 ----------
    /** 请求体字段校验失败（由 Bean Validation 触发）。 */
    VALIDATION_FAILED(40000, HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    /** 请求参数本身不合法（业务规则层面的前置判断）。 */
    BAD_REQUEST(40001, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    /** 参数类型无法转换，例如把 "abc" 传给 int 型参数。 */
    TYPE_MISMATCH(40002, HttpStatus.BAD_REQUEST, "参数类型不正确"),
    /** 缺少必填参数。 */
    MISSING_PARAMETER(40003, HttpStatus.BAD_REQUEST, "缺少必要参数"),
    /** identity 模块：密码不满足密码策略（长度、常见弱密码表、与账号相关性）。 */
    PASSWORD_POLICY_VIOLATION(40010, HttpStatus.BAD_REQUEST, "密码不符合安全要求"),

    // ---------- 400（community 模块，Phase 03 起） ----------
    /** community 模块：请求里给出的板块标识不存在。 */
    CATEGORY_NOT_FOUND(40030, HttpStatus.BAD_REQUEST, "所选板块不存在"),
    /**
     * community 模块：帖子内容不符合要求（正文超长、标签数量超限等）。
     *
     * <p>与 {@link #VALIDATION_FAILED} 的分工是刻意的：
     * 前者管"字段格式"（必填、长度上限这类由 Bean Validation 声明的结构约束），
     * 本码管"业务策略"（上限取自配置、将来会被压测调整的那些）。
     * 合成一个码之后，前端就无法区分"我少填了一个字段"与"我写得太长了"。
     */
    INVALID_POST_CONTENT(40031, HttpStatus.BAD_REQUEST, "帖子内容不符合要求"),
    /** community 模块：标签名无法规范化为有效标识（例如整串都是标点符号）。 */
    INVALID_TAG(40032, HttpStatus.BAD_REQUEST, "标签名不合法"),

    // ---------- 400（workspace 模块，Phase 04 起） ----------
    /**
     * workspace 模块：邀请码不存在、不是发给本人的、已过期或已撤销。
     *
     * <p>文案刻意不区分这四种情况。区分它们等于把邀请码变成可试探的对象：
     * 攻击者可以据此判断"某个码存在但过期了"，从而缩小猜测范围。
     *
     * <p>但"不是发给本人的码"连本码都不会返回 —— 那一支走 {@link #NOT_FOUND}，
     * 因为它连"这条邀请存在"都不该被确认。
     */
    INVITE_CODE_INVALID(40020, HttpStatus.BAD_REQUEST, "邀请无效或已过期"),
    /**
     * workspace 模块：空间成员数已达上限。
     *
     * <p>用 400 而不是 429：这是"这次请求本身不可能成功"，不是"稍后再试"。
     * 限流说的是节奏问题，这个是策略上限。
     */
    WORKSPACE_MEMBER_LIMIT(40021, HttpStatus.BAD_REQUEST, "空间成员数已达上限"),
    /**
     * workspace 模块：空间内容不符合要求（名称、描述、笔记正文、文件名等）。
     *
     * <p>与 {@link #INVALID_POST_CONTENT} 的分工相同：本码管"业务策略"
     * （上限取自配置、将来会被调整），字段格式约束仍归 {@link #VALIDATION_FAILED}。
     */
    INVALID_WORKSPACE_CONTENT(40022, HttpStatus.BAD_REQUEST, "空间内容不符合要求"),
    /**
     * workspace 模块：拥有者不能退出自己的空间。
     *
     * <p>这不是限制，而是"没有转让就没有退出"这个事实的说明。若允许拥有者退出，
     * 空间会变成一个没有人能删除、没有人能管理成员、也没有人能改设置的孤儿 ——
     * 而库里没有任何东西能表达"这个空间无主"。所以正确的路径是先转让，或者直接删除。
     */
    WORKSPACE_OWNER_CANNOT_LEAVE(40023, HttpStatus.BAD_REQUEST, "空间拥有者不能退出，请先转让或删除空间"),
    /**
     * workspace 模块：短期下载链接无效或已过期。
     *
     * <h2>为什么不复用 ACCESS_DENIED 或 NOT_FOUND</h2>
     * 这条链接<b>绕开了请求头里的凭据</b>，因此拿到它的客户端没有别的补救手段 ——
     * 它必须知道"链接失效了，请重新申请一个"。返回 403 会让它去找管理员，
     * 而管理员在这件事上什么也做不了；返回 404 会让它以为文档被删了，
     * 而文档好好的。
     *
     * <p>把"链接过期"与"没有权限"分成两个码，客户端才能给出正确的下一步提示 ——
     * 这是错误码存在的理由本身，而不只是分类整洁。
     */
    DOWNLOAD_LINK_INVALID(40024, HttpStatus.BAD_REQUEST, "下载链接无效或已过期，请重新获取"),

    // ---------- 401 ----------
    /** 未提供凭据。用于"根本没带令牌"的情况。 */
    UNAUTHENTICATED(40100, HttpStatus.UNAUTHORIZED, "请先登录"),
    /**
     * 访问令牌已过期。
     *
     * <p>与 {@link #TOKEN_INVALID} 分开，是因为客户端的处理方式不同：
     * 过期意味着"用刷新令牌换一个新的"（可自动恢复），无效意味着"重新登录"。
     * 合成一个码会让前端只能一刀切地要求用户重新登录，白白增加摩擦。
     */
    TOKEN_EXPIRED(40101, HttpStatus.UNAUTHORIZED, "登录状态已过期，请重新登录"),
    /**
     * 访问令牌无法通过校验（签名不符、格式错误、iss 不匹配等）。
     *
     * <p>对外文案刻意笼统：具体是哪一步失败属于内部实现，回给调用方只会
     * 帮助攻击者试探校验逻辑，对正常用户毫无价值。
     */
    TOKEN_INVALID(40102, HttpStatus.UNAUTHORIZED, "登录凭据无效，请重新登录"),
    /**
     * 用户名或密码错误。
     *
     * <p><b>刻意与"账号不存在"共用同一个码与同一句文案</b>：
     * 若"用户不存在"和"密码错误"给出不同响应，接口就变成了账号枚举器 ——
     * 攻击者可以逐个试出平台上有哪些账号，为后续的定向钓鱼与撞库提供名单。
     */
    BAD_CREDENTIALS(40103, HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    /** 令牌已被撤销（登出、改密、踢下线，或令牌世代号已推进）。 */
    TOKEN_REVOKED(40104, HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录"),

    // ---------- 403 ----------
    /** 已认证但权限不足（缺少所需权限点）。 */
    ACCESS_DENIED(40300, HttpStatus.FORBIDDEN, "没有执行该操作的权限"),
    /**
     * 账号当前不可用（被锁定或被停用）。
     *
     * <p>只有在密码校验**通过之后**才允许返回本错误：否则"锁定"就成了账号存在性探针。
     */
    ACCOUNT_NOT_USABLE(40301, HttpStatus.FORBIDDEN, "账号当前不可用"),

    // ---------- 404 ----------
    /** 目标资源不存在。注意：无权访问时对外也应表现为 404，避免泄漏资源是否存在。 */
    NOT_FOUND(40400, HttpStatus.NOT_FOUND, "资源不存在"),

    // ---------- 405 / 415 ----------
    /** HTTP 方法不支持。 */
    METHOD_NOT_ALLOWED(40500, HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持"),
    /** 请求媒体类型不支持。 */
    UNSUPPORTED_MEDIA_TYPE(41500, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的内容类型"),

    // ---------- 409 ----------
    /** 唯一性冲突（用户名、邮箱等已被占用）。 */
    CONFLICT(40900, HttpStatus.CONFLICT, "数据已存在"),

    // ---------- 413 ----------
    /**
     * 请求体过大。
     *
     * <h2>为什么它必须存在，而不是让 {@code MaxUploadSizeExceededException} 落到兜底分支</h2>
     * 分片上传超过 {@code spring.servlet.multipart.max-file-size} 时，
     * 异常在<b>进入控制器之前</b>就由容器抛出。若不专门处理它，
     * 它会落到"未预期异常"那一支，变成 {@code 50000 服务器内部错误} ——
     * 而调用方看到 5xx 时的第一反应是重试，于是同一个超大文件被反复上传。
     *
     * <p>它与 {@code app.workspace.documents.max-size-bytes} 是两个不同的闸门：
     * 这一层是容器的硬上限（在解析请求体时就拦下），应用配置的那一层更小，
     * 让"文件太大"由业务自己判断并返回统一错误信封。
     */
    PAYLOAD_TOO_LARGE(41300, HttpStatus.PAYLOAD_TOO_LARGE, "请求内容过大"),

    // ---------- 429 ----------
    /**
     * 请求过于频繁。
     *
     * <p>响应必须带 {@code Retry-After} 头，让调用方知道"多久之后可以再试"，
     * 而不是自己盲猜重试节奏 —— 盲猜的结果通常是客户端越试越密，把限流变成雪上加霜。
     */
    RATE_LIMITED(42900, HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试"),

    // ---------- 5xx ----------
    /** 未预期的服务端异常。对外不暴露内部细节，细节只进日志。 */
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后重试"),
    /** 依赖的外部系统暂不可用。 */
    DEPENDENCY_UNAVAILABLE(50300, HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂时不可用，请稍后重试");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
