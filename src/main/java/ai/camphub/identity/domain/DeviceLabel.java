package ai.camphub.identity.domain;

/**
 * 会话设备标识的规范化。
 *
 * <h2>为什么需要这样一个类</h2>
 * 设备标识由客户端上报，仅用于展示（"查看并单独登出设备"），<b>不作为安全判据</b>。
 * 但它在数据库里是 {@code NOT NULL}，而接口上又是可选字段 ——
 * "可选"与"非空"之间必须有人负责补齐，否则不传 device 的注册请求会以
 * {@code Column 'device' cannot be null} 变成 500：一个完全合法的用户操作
 * 表现为服务器错误。
 *
 * <p>它之所以值得从各处私有的 {@code normalizeDevice} 提升为一个独立的领域类：
 * 会创建会话的路径有三条（注册、登录、令牌轮换）。当规范化逻辑只写在其中一条上时，
 * 另外两条的缺失<b>在编译期与单测里都不会暴露</b>，只在特定输入下变成 500。
 * 收敛到一处，这个分歧才不可能再次出现。
 *
 * <h2>为什么不直接信任客户端的值</h2>
 * 超长值会让插入直接失败 —— 把"客户端乱填字段"变成一次 500，
 * 等于给攻击者提供了低成本制造错误日志的手段。因此这里做长度截断，
 * 由入口负责把输入裁剪到列宽以内，而不是让数据库抛异常。
 *
 * <p>截断而不是拒绝，是因为它不是安全判据：截断只影响展示文本，
 * 不会让任何人获得本不该有的权限。
 */
public final class DeviceLabel {

    /** 设备标识的列宽上限，与 {@code refresh_token.device} 的 VARCHAR(64) 一致。 */
    private static final int MAX_LENGTH = 64;

    /** 未上报设备时的缺省值。用一个可读的字符串而不是空串：列表里空串看不出含义。 */
    public static final String UNKNOWN = "unknown";

    private DeviceLabel() {
    }

    /**
     * 规范化设备标识。
     *
     * @param device 客户端上报的原始值，可为 null
     * @return 非 null 的规范值；null 或空白返回 {@link #UNKNOWN}
     */
    public static String normalize(String device) {
        if (device == null || device.isBlank()) {
            return UNKNOWN;
        }
        String trimmed = device.trim();
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
