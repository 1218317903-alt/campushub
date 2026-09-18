package ai.camphub.workspace.app;

import java.io.InputStream;

/**
 * 一次上传请求的应用层表示。
 *
 * <h2>为什么不用 {@code MultipartFile}</h2>
 * 那是 Spring Web 的类型。让应用层依赖它，意味着服务层的方法只能由 HTTP 请求驱动 ——
 * 想为它写一个不经过 Servlet 的测试就得去构造一个假的 multipart 对象，
 * 而想从别处（例如将来的导入功能）复用它也无从下手。
 * 这里只保留真正需要的四样东西：文件名、声明的内容类型、字节数、内容流。
 *
 * <p>这四个字段里<b>有三个来自用户，全部不可信</b>：
 * 文件名可能含路径分隔符或控制字符，内容类型可能是任意字符串，
 * 字节数可能与实际内容不符。它们的校验在 {@link DocumentService} 里，
 * 而不是在这里 —— 一个只做数据承载的 record 不应该同时承担策略。
 *
 * @param fileName    原始文件名（不可信）
 * @param contentType 客户端声明的内容类型（不可信）
 * @param sizeBytes   客户端声明的字节数（不可信）
 * @param content     内容流；由服务层负责消费
 */
public record DocumentUpload(
        String fileName,
        String contentType,
        long sizeBytes,
        InputStream content
) {
}
