package ai.camphub.workspace.api;

import ai.camphub.workspace.app.DownloadLink;
import java.time.Instant;

/**
 * 短期下载链接的对外表示。
 *
 * <h2>为什么要把 {@code direct} 与 {@code expiresAt} 都告诉客户端</h2>
 * 一个只返回 URL 的响应会迫使客户端把"这个链接什么时候失效"当成约定去猜 ——
 * 而猜错的两种后果都不轻：猜长了会在用户点下去时得到一个 40024，
 * 猜短了会在链接还能用的时候就把界面上的按钮灰掉。
 * 把到期时间作为数据返回，是让前端不必拥有这个知识。
 *
 * <p>{@code direct} 的含义与取舍见
 * {@code DocumentController#createDownloadLink}。
 *
 * @param url       可直接使用的下载地址
 * @param direct    是否绕过应用、由对象存储直接返回字节
 * @param expiresAt 失效时间
 */
public record DownloadLinkResponse(String url, boolean direct, Instant expiresAt) {

    /**
     * 从应用层对象构造。
     *
     * @param link 下载链接
     * @return 响应
     */
    public static DownloadLinkResponse from(DownloadLink link) {
        return new DownloadLinkResponse(link.url().toString(), link.direct(), link.expiresAt());
    }
}
