package ai.camphub.community.app;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class FeedQueryTest {
    @Test
    void offsetUsesCappedPageSizeAndLongArithmetic() {
        assertThat(FeedQuery.of(null, null, null, 2, 1000).offset(50)).isEqualTo(50L);
        assertThat(FeedQuery.of(null, null, null, Integer.MAX_VALUE, 50).offset(50))
                .isEqualTo(107374182300L);
    }
}
