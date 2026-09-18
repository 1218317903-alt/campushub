package ai.camphub.identity.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.camphub.common.config.RequestProperties;
import ai.camphub.common.error.RateLimitedException;
import ai.camphub.identity.config.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuthRateLimiterTest {
    private final AtomicLong now = new AtomicLong();

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private AuthRateLimiter limiter() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(ignored -> now.get());
        RequestProperties request = mock(RequestProperties.class);
        var longRule = new SecurityProperties.RateLimit.Rule(1, Duration.ofMinutes(10));
        var shortRule = new SecurityProperties.RateLimit.Rule(1, Duration.ofSeconds(1));
        return new AuthRateLimiter(request, clock,
                new SecurityProperties(null, null, null,
                        new SecurityProperties.RateLimit(longRule, shortRule, shortRule)));
    }

    private void source(int index) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0." + (index / 256) + "." + (index % 256));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void rejectsNewSourcesAtCapacityWithoutDroppingExistingWindows() {
        var limiter = limiter();
        for (int i = 0; i < 10_000; i++) {
            source(i);
            limiter.checkLogin();
        }
        source(10_000);
        assertThatThrownBy(limiter::checkLogin).isInstanceOf(RateLimitedException.class);
        source(0);
        assertThatThrownBy(limiter::checkLogin).isInstanceOf(RateLimitedException.class);
        now.set(Duration.ofMinutes(10).toMillis());
        source(10_000);
        assertThatCode(limiter::checkLogin).doesNotThrowAnyException();
    }

    @Test
    void shortWindowMustNotEvictUnexpiredLongWindow() {
        var limiter = limiter();
        for (int i = 0; i < 10_000; i++) {
            source(i);
            limiter.checkLogin();
        }
        now.set(2_000);
        source(10_000);
        assertThatThrownBy(limiter::checkRegister).isInstanceOf(RateLimitedException.class);
        source(0);
        assertThatThrownBy(limiter::checkLogin).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void bucketsHaveIndependentBudgetsAndExpiration() {
        var limiter = limiter();
        source(0);
        limiter.checkLogin();
        limiter.checkRegister();
        assertThatThrownBy(limiter::checkRegister).isInstanceOf(RateLimitedException.class);
        now.set(1_000);
        assertThatCode(limiter::checkRegister).doesNotThrowAnyException();
        assertThatThrownBy(limiter::checkLogin).isInstanceOf(RateLimitedException.class);
    }
}
