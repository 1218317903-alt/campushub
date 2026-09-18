package ai.camphub.workspace.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceDocument;
import ai.camphub.workspace.infrastructure.DocumentMapper;
import ai.camphub.workspace.infrastructure.WorkspaceMapper;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 短期下载令牌的兑换：把一枚令牌换成文件内容。
 *
 * <h2>这是三层防线唯一的例外，因此它单独一个类</h2>
 * 其它所有读写私有数据的应用服务方法，第一行都是
 * {@code workspaceService.requireAccessFor(...)}。这里没有 ——
 * 因为发起请求的<b>不是一个已登录的用户</b>，而是一枚令牌。
 * 把这段逻辑放在自己的类里（而不是塞进 {@code DocumentService} 多一个方法），
 * 是为了让"哪些路径不受三层防线保护"成为一个能一眼看完的清单。
 *
 * <h2>它的安全性来自三件事，而不是来自"没检查"</h2>
 * <ol>
 *   <li><b>令牌只能由已经通过三层防线的路径签发。</b>
 *       见 {@code DocumentService#createDownloadLink}：那里先走完整的
 *       {@code requireAccessFor} + {@code requireStoredDocument}，
 *       只有通过了才会调用签发。因此"谁能拿到链接"与"谁能点下载"是同一个集合。</li>
 *   <li><b>签名覆盖了工作空间与文档标识以及到期时间。</b>
 *       改任何一个字节都会导致校验失败，因此不存在"把别人的文档标识填进去"。</li>
 *   <li><b>时限极短。</b>默认 5 分钟，它就是链接泄漏后的暴露窗口。</li>
 * </ol>
 *
 * <h2>第三层防线在这里仍然生效 —— 这是刻意的</h2>
 * 拿到令牌后<b>不</b>去调用标注了 {@code @Unscoped} 的查询来绕过过滤，
 * 而是把"这枚令牌恰好授权这一个空间"这件事绑定进
 * {@link WorkspaceScopeContext}，然后照常使用被限制的
 * {@code DocumentMapper#findByPublicId}。
 *
 * <p>两者都能跑通，差别在防线失效时会发生什么：用 {@code @Unscoped} 的话，
 * 这条路径永久不受第三层保护，将来任何一次改动（多查一张表、换个条件）
 * 都不会有兜底；绑定范围则保留了兜底 —— 即使有人把查询条件改错，
 * SQL 上那道 {@code workspace_id IN (...)} 仍然只放行这一个空间。
 * <b>唯一授权范围的绑定不会削弱防线，只会把它收窄。</b>
 *
 * <h2>为什么这里不记审计</h2>
 * 审计的 {@code actor} 是用户主键，而这条路径上没有用户。写一个"来源未知"的
 * 占位值会让审计表里出现一批无法归因的行，反而降低它的可信度。
 * 需要归因的事件在<b>签发</b>时已经记下（{@code DOCUMENT_DOWNLOAD_LINK}）——
 * 而令牌的设计前提正是"签发即授权"，所以那一条就是这条链路的问责起点。
 */
@Service
public class TokenDownloadService {

    private final DownloadTokenService downloadTokens;
    private final WorkspaceMapper workspaceMapper;
    private final DocumentMapper documentMapper;
    private final ObjectStorage objectStorage;

    /**
     * 构造注入。
     *
     * @param downloadTokens 令牌签发与校验
     * @param workspaceMapper 空间数据访问（按 public_id 解析，属于鉴权输入）
     * @param documentMapper  文档数据访问
     * @param objectStorage   对象存储端口
     */
    public TokenDownloadService(DownloadTokenService downloadTokens,
                                WorkspaceMapper workspaceMapper,
                                DocumentMapper documentMapper,
                                ObjectStorage objectStorage) {
        this.downloadTokens = downloadTokens;
        this.workspaceMapper = workspaceMapper;
        this.documentMapper = documentMapper;
        this.objectStorage = objectStorage;
    }

    /**
     * 用令牌换取文件内容。
     *
     * @param token 令牌
     * @return 内容流
     * @throws BusinessException 令牌无效或过期时 {@code 40024}；
     *                           空间或文档不存在时 {@code 40400}；
     *                           字节已被清理时 {@code 50300}
     */
    @Transactional(readOnly = true)
    public DocumentDownload redeem(String token) {
        DownloadTokenService.DownloadTicket ticket = downloadTokens.requireValid(token);

        Workspace workspace = workspaceMapper.findByPublicId(ticket.workspacePublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        // 令牌说"这一个空间"，那就只绑定这一个。绑定之后这一层的过滤照常生效，
        // 而它能够放行的最大范围已经被令牌本身限死了。
        WorkspaceScopeContext.bind(Set.of(workspace.id()));

        WorkspaceDocument document = documentMapper
                .findByPublicId(workspace.id(), ticket.docPublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        if (!document.hasStoredContent()) {
            // 与请求路径同一个判定：元数据在、字节不在。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return new DocumentDownload(document.name(), document.sizeBytes(),
                objectStorage.open(document.storageKey()));
    }
}
