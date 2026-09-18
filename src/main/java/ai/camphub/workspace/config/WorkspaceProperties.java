package ai.camphub.workspace.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 空间模块配置（前缀 {@code app.workspace}）。
 *
 * <h2>配置绑不上时不会报错，这是本类最需要防的一件事</h2>
 * 与 {@code CommunityProperties} 完全相同的风险：record 的构造函数绑定在属性缺失时
 * <b>不报错</b>，嵌套组件会变成 {@code null}；若 {@code application.yml} 把前缀写成
 * 顶层 {@code workspace:}，整块配置会被静默忽略，而应用仍然正常启动。
 * 因此 {@code ApplicationConfigStructureTest} 会断言这些键在配置文件里真实存在。
 *
 * @param limits    空间基础字段的输入约束
 * @param members   成员与邀请约束
 * @param notes     笔记输入约束
 * @param documents 文档存储与上传约束
 * @param feed      列表分页约束
 */
@ConfigurationProperties(prefix = "app.workspace")
public record WorkspaceProperties(
        Limits limits,
        Members members,
        Notes notes,
        Documents documents,
        Feed feed
) {

    /**
     * 空间基础字段约束。
     *
     * <p>上限与 {@code workspace.name VARCHAR(80)} / {@code description VARCHAR(500)}
     * 对齐 —— 调大到超过列宽会让插入报错，两处必须一起改。
     *
     * @param maxNameLength        空间名称最大字符数
     * @param maxDescriptionLength 空间描述最大字符数
     */
    public record Limits(int maxNameLength, int maxDescriptionLength) {
    }

    /**
     * 成员与邀请约束。
     *
     * @param maxMembers       单个空间的最大成员数（不含拥有者）。
     *                         <b>这是资源保护上限</b>：成员列表、成员校验都会被它约束住大小
     * @param inviteValidHours 邀请码有效期（小时）。取值理由见 {@code docs/resource-authorization.md}：
     *                         太短会让"发出去还没来得及点"变成常态，太长等于给一个长期有效的入门口令
     */
    public record Members(int maxMembers, int inviteValidHours) {
    }

    /**
     * 笔记输入约束。
     *
     * @param maxTitleLength 标题最大字符数，与 {@code note.title VARCHAR(200)} 对齐
     * @param maxBodyLength  正文最大字符数，与 {@code app.community.post.max-body-length} 取同一个值。
     *                       <b>它是资源保护上限而非体验问题</b>：正文每次写入都要被渲染成 HTML
     *                       并跑一遍白名单净化，代价与输入长度成正比
     * @param summaryLength  列表摘要长度，与 {@code note.summary VARCHAR(300)} 对齐
     */
    public record Notes(int maxTitleLength, int maxBodyLength, int summaryLength) {
    }

    /**
     * 文档存储与上传约束。
     *
     * @param maxSizeBytes  单个文件最大字节数。<b>与 {@code spring.servlet.multipart.max-file-size}
     *                      是两个不同的闸门</b>：multipart 那一层是容器级的硬上限（超了直接
     *                      在进入控制器之前就被拒），这里的值刻意更小，让"文件太大"由应用自己
     *                      判断并返回统一错误信封，而不是让容器抛出一个形状不同的异常
     * @param storageDir    本地存储根目录。Phase 05 接入对象存储后本项会被替换成
     *                      bucket 与端点配置，因此代码里对它的引用只应存在于适配器装配处
     * @param allowedTypes  允许声明的 MIME 类型白名单。用白名单而不是黑名单
     * @param downloadInline 下载时是否允许浏览器内联打开。<b>默认 false，且不建议改动</b>：
     *                      内联打开意味着用户上传的内容会在本站域的源下被浏览器解析渲染，
     *                      这正是"上传一个 HTML 就得到一个 XSS"的成因
     */
    public record Documents(long maxSizeBytes,
                            String storageDir,
                            List<String> allowedTypes,
                            boolean downloadInline) {
    }

    /**
     * 列表分页约束。
     *
     * @param defaultPageSize 未指定时使用的页大小
     * @param maxPageSize     服务端强制的上限；超过上限按上限截断而不是报错
     */
    public record Feed(int defaultPageSize, int maxPageSize) {
    }
}
