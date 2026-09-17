package ai.camphub.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 设备标识规范化测试。
 *
 * <p>这一组断言看起来琐碎，但它挡住的是一个真实发生过的缺陷：注册接口的 {@code device}
 * 是可选字段，而 {@code refresh_token.device} 是 {@code NOT NULL}，
 * 早期版本只在令牌轮换路径做了规范化，于是"注册时不传 device"直接变成 500。
 * 这种输入在手工点测时几乎必然会被漏掉 —— 前端总会带上 device。
 */
class DeviceLabelTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("未上报设备或只给了空白：归一为 unknown，绝不返回 null")
    void shouldFallBackToUnknown(String device) {
        assertThat(DeviceLabel.normalize(device)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("正常设备名保留，并去掉首尾空白")
    void shouldTrimAndKeep() {
        assertThat(DeviceLabel.normalize("  Chrome on macOS  ")).isEqualTo("Chrome on macOS");
    }

    @Test
    @DisplayName("超长值被截断到列宽，而不是让数据库抛异常")
    void shouldTruncateToColumnWidth() {
        String oversized = "x".repeat(500);

        String normalized = DeviceLabel.normalize(oversized);

        assertThat(normalized).hasSize(64);
        // 截断而不是拒绝：它只影响列表里的展示文本，不构成任何权限判据
        assertThat(DeviceLabel.normalize(oversized)).isEqualTo("x".repeat(64));
    }

    @Test
    @DisplayName("恰好等于列宽的值不被改动")
    void shouldKeepExactBoundaryValue() {
        String exactly = "y".repeat(64);

        assertThat(DeviceLabel.normalize(exactly)).isEqualTo(exactly);
    }
}
