package ai.camphub.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ErrorCode} 的编码规则自检。
 *
 * <p>这类测试的价值在于：错误码一旦对外发布就很难修改。用测试把编码规则钉住，
 * 后人在新增错误码时如果写错了前缀（例如把 404 的错误码写成 400xx），
 * 构建会立刻失败，而不是等到前端按 HTTP 状态分支处理时才发现对不上。
 */
class ErrorCodeTest {

    @Test
    @DisplayName("错误码前三位必须等于其 HTTP 状态码，且 message 非空")
    void codePrefixMustMatchHttpStatus() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            int expectedPrefix = errorCode.httpStatus().value() * 100;
            assertThat(errorCode.code())
                    .as("错误码 %s 的前缀应与 HTTP 状态 %s 一致", errorCode.name(), errorCode.httpStatus())
                    .isBetween(expectedPrefix, expectedPrefix + 99);
            assertThat(errorCode.defaultMessage())
                    .as("错误码 %s 必须有面向用户的默认文案", errorCode.name())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("错误码不允许重复")
    void codesMustBeUnique() {
        List<Integer> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("错误码必须是 5 位数字")
    void codesMustBeFiveDigits() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(String.valueOf(errorCode.code()))
                    .as("错误码 %s 应为 5 位", errorCode.name())
                    .hasSize(5)
                    .containsOnlyDigits();
        }
    }
}
