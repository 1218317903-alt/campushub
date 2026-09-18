package ai.camphub.workspace.app;

import java.net.URI;
import java.time.Instant;

/**
 * 一次性下载链接。
 *
 * <h2>为什么必须带上 {@code direct}</h2>
 * 两种后端给出的链接在<b>对外契约上</b>是一样的：一个在时限内可直接使用、
 * 不需要额外凭据的下载地址。但它们在<b>系统行为上</b>完全不同：
 * <ul>
 *   <li>{@code direct = true}（对象存储）：浏览器直连存储服务，
 *       内容字节不经过本应用。带宽与内存开销都在存储侧。</li>
 *   <li>{@code direct = false}（本地磁盘）：链接指向本应用的一个端点，
 *       字节要由本应用读出来再写出去。</li>
 * </ul>
 * 把这件事显式暴露出来，是因为它回答的正是容量规划要问的问题：
 * "一千个人同时点下载，压力落在谁身上"。接口的最后一段也能据此
 * 决定要不要用预签名链接（例如在反向代理未配置时，直连地址可能不可达）。
 *
 * <p>隐藏这个差异、只返回一个 URL，会让调用方无法判断自己拿到的是哪一种 ——
 * 而那两种在故障时的表现相差很大。
 *
 * @param url       下载地址
 * @param direct    是否绕开应用服务器
 * @param expiresAt 失效时间
 */
public record DownloadLink(URI url, boolean direct, Instant expiresAt) {
}
