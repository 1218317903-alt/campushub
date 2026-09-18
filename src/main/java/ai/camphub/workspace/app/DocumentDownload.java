package ai.camphub.workspace.app;

import java.io.InputStream;

/**
 * 一次下载的结果。
 *
 * <h2>为什么不是直接返回字节数组</h2>
 * 文件大小上限是配置里的几十 MiB 量级，把它整份读进堆内存意味着
 * 并发几个下载就能把应用推近 OOM —— 而这是一个只读接口，看起来毫无风险。
 * 返回流让内容以固定大小的缓冲区流过，内存占用与文件大小无关。
 *
 * <p>流由调用方负责关闭。这一点必须写清楚：Servlet 容器会在响应完成后关闭流，
 * 但如果调用方在半途放弃（客户端断开），它仍然需要自己关掉，
 * 否则文件句柄会挂到下一次 GC。
 *
 * @param fileName    要写进 {@code Content-Disposition} 的文件名（已净化）
 * @param sizeBytes   字节数，用于 {@code Content-Length}
 * @param content     内容流，由调用方关闭
 */
public record DocumentDownload(
        String fileName,
        long sizeBytes,
        InputStream content
) {
}
