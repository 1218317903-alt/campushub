package ai.camphub.workspace.app;

import ai.camphub.workspace.domain.ChunkDraft;
import ai.camphub.workspace.domain.ParsedDocument;
import java.util.List;

/**
 * 一次"解析 + 分块"的完整产出。
 *
 * <h2>为什么把两者绑在一起返回</h2>
 * 它们的<b>写入必须在同一个事务里</b>：分块表里出现的每一行都应当对应一个
 * 已经进入 {@code READY} 的文档，反之亦然。若接口把它们分成两次调用返回，
 * 调用方就可能在写完分块之后、更新状态之前失败，留下一批
 * "存在但所属文档声称尚未解析完"的块 —— 而那些块会被检索到。
 *
 * <p>绑成一个值对象之后，"这两件事一起成立"成为调用方无法绕过的事实，
 * 而不是一条需要它记得遵守的约定。
 *
 * @param parsed 解析产出（段落与解析器版本）
 * @param chunks 切分后的分块，序号自 0 连续
 */
public record ParseOutcome(ParsedDocument parsed, List<ChunkDraft> chunks) {
}
