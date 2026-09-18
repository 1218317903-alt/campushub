package ai.camphub.workspace.app;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * 对象存储端口：文档字节的读写删。
 *
 * <h2>为什么在只有一个实现的时候就有这个端口</h2>
 * 本阶段的实现是本地磁盘（{@code LocalFileObjectStorage}），它是真实的存储后端 ——
 * 上传与下载都能跑通并被验收，不是占位实现。端口存在的理由是另一件事：
 * <b>上传接口的对外形状必须一次定下来</b>。
 *
 * <p>若这一阶段让上传接口返回 503、等接入对象存储时再实现，那么
 * "请求体是 multipart 还是 JSON""字段叫什么""响应里返回什么"
 * 这些契约都要在下一阶段重定一次，而每一次重定都是一次对外契约变更。
 * 有了端口，替换后端时改动只发生在一个 {@code @Bean} 方法里，
 * 应用层与对外契约都不动。
 *
 * <h2>为什么端口在 app 包而不是 infrastructure</h2>
 * 它是"应用需要什么能力"的声明，不是"某个具体存储怎么实现"。
 * 适配器在 {@code infrastructure.storage} 下实现它，依赖方向是
 * 基础设施 → 应用，这与本项目其余部分一致。
 */
public interface ObjectStorage {

    /**
     * 写入内容，返回最终的存储键。
     *
     * <h2>为什么存储键由实现决定，而不是调用方传进来</h2>
     * "键长什么样"取决于存储后端（本地磁盘要分目录避免单目录拥塞，
     * 对象存储则更在意前缀的可枚举性），把这件事实留给实现，
     * 应用层只需要"存进去、拿回一个键"，换后端时应用层不用改。
     *
     * <h2>为什么是 keyHint 而不是完整的键</h2>
     * 调用方提供的是一个<b>建议前缀</b>（本项目用文档的 {@code public_id}），
     * 实现负责把它拼成一个安全的键。这样"用户可控的字符串会不会进入路径"
     * 这个问题只有一个地方需要回答 —— 实现里那句格式校验。
     *
     * @param keyHint   建议前缀。<b>必须</b>匹配 {@code [A-Za-z0-9_-]{1,64}}，
     *                  实现必须校验并拒绝其它字符（见下）
     * @param content   内容流；由实现负责关闭
     * @param sizeBytes 期望的字节数，用于校验实际写入的完整性
     * @return 最终存储键，写入 {@code document.storage_key}
     */
    String store(String keyHint, InputStream content, long sizeBytes);

    /**
     * 打开存储键对应的内容流。
     *
     * @param storageKey 存储键
     * @return 内容流；<b>由调用方负责关闭</b>
     */
    InputStream open(String storageKey);

    /**
     * 删除存储键对应的内容。
     *
     * <p>键不存在时静默返回。删除的语义是"让它不存在"，
     * 报错只会让调用方（原本就在做幂等清理）多写一段毫无收益的容错。
     *
     * @param storageKey 存储键
     */
    void delete(String storageKey);

    /**
     * 生成一个<b>绕开应用服务器</b>的直接读取地址，若后端支持。
     *
     * <h2>为什么返回 {@code Optional} 而不是直接返回 URL</h2>
     * 这个方法的语义是精确的：<b>"客户端能不能不经过本应用就去读取内容"</b>。
     * 对象存储能（预先签名的 URL 由存储服务自己校验），本地磁盘不能 ——
     * 字节就在本应用的磁盘上，没有任何"绕开"可言。
     *
     * <p>让本地实现返回 {@code Optional.empty()}，是把"不能"如实表达出来，
     * 而不是返回一个实际上仍然经过应用的地址来假装支持。应用层据此选择
     * 另一条路径（本应用签发的短期令牌链接），而那两件事的区别是真实的：
     * 前者省掉一次应用侧的数据搬运，后者不省。
     *
     * <p>反过来，若签名设计成"总返回一个 URL"，本地实现就只能返回一个
     * 指向自己的地址 —— 于是调用方再也无法区分"这个链接会不会经过我"，
     * 而这正是容量规划时要回答的问题。
     *
     * <h2>实现必须做到的两件事</h2>
     * <ol>
     *   <li><b>地址必须有时限</b>，且不得超过传入的 {@code ttl}。一个永久的
     *       直链等同于把私有内容公开 —— 它会被转发、被收藏、被搜索到。</li>
     *   <li><b>地址必须带下载文件名</b>（若后端支持该参数），
     *       否则浏览器会用一个存储键式的随机名保存文件。</li>
     * </ol>
     *
     * @param storageKey   存储键
     * @param downloadName 建议的下载文件名；仅用于响应头，绝不参与路径拼接
     * @param ttl          有效时长
     * @return 直链；后端不支持时为空
     */
    Optional<URI> directGetUrl(String storageKey, String downloadName, Duration ttl);
}
