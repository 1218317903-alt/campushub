package ai.camphub.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiError} 契约测试。
 *
 * <p>测试重点放在"调用方会依赖哪些保证"，而不是逐字段复述构造过程：
 * details 永不为 null、不可被外部修改、时间戳存在。这些才是前端代码会依赖的性质。
 */
class ApiErrorTest {

    @Test
    @DisplayName("不带明细时 details 是空列表而不是 null")
    void detailsShouldNeverBeNull() {
        ApiError error = ApiError.of(ErrorCode.NOT_FOUND, "trace-abc", "/api/v1/posts/1");

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND.code());
        assertThat(error.message()).isEqualTo(ErrorCode.NOT_FOUND.defaultMessage());
        assertThat(error.traceId()).isEqualTo("trace-abc");
        assertThat(error.path()).isEqualTo("/api/v1/posts/1");
        assertThat(error.timestamp()).isNotNull();
        assertThat(error.details()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("自定义文案不影响错误码")
    void customMessageShouldNotAffectCode() {
        ApiError error = ApiError.of(ErrorCode.TYPE_MISMATCH, "参数 [id] 类型不正确", "trace", "/x");

        assertThat(error.code()).isEqualTo(ErrorCode.TYPE_MISMATCH.code());
        assertThat(error.message()).isEqualTo("参数 [id] 类型不正确");
    }

    @Test
    @DisplayName("details 是不可变副本，外部修改原集合不会影响响应体")
    void detailsShouldBeDefensiveCopy() {
        List<ApiError.FieldViolation> source = new ArrayList<>();
        source.add(new ApiError.FieldViolation("title", "不能为空"));

        ApiError error = ApiError.withDetails(ErrorCode.VALIDATION_FAILED, "trace", "/x", source);
        source.add(new ApiError.FieldViolation("body", "不能为空"));

        assertThat(error.details()).hasSize(1);
        assertThatThrownBy(() -> error.details().add(new ApiError.FieldViolation("a", "b")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("字段明细保留字段名与原因")
    void fieldViolationShouldKeepBothParts() {
        ApiError error = ApiError.withDetails(ErrorCode.VALIDATION_FAILED, "trace", "/x",
                List.of(new ApiError.FieldViolation("username", "长度必须在 3 到 20 之间")));

        assertThat(error.details()).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.field()).isEqualTo("username");
                    assertThat(violation.reason()).isEqualTo("长度必须在 3 到 20 之间");
                });
    }
}
