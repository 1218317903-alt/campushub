package ai.camphub.identity.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.camphub.common.error.BusinessException;
import ai.camphub.identity.domain.RefreshTokenRecord;
import ai.camphub.identity.domain.User;
import ai.camphub.identity.infrastructure.RefreshTokenMapper;
import ai.camphub.identity.infrastructure.UserMapper;
import ai.camphub.platform.audit.app.AuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 确定性覆盖并发请求读到旧快照、但条件更新已失败的窗口。 */
class RefreshTokenServiceTest {
    @Test
    void lostRotationRace_mustNotIssueAnotherSession() {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        RefreshTokenMapper tokens = mock(RefreshTokenMapper.class);
        UserMapper users = mock(UserMapper.class);
        SessionService sessions = mock(SessionService.class);
        AuditService audit = mock(AuditService.class);
        RefreshTokenLeakHandler leaks = mock(RefreshTokenLeakHandler.class);
        User user = mock(User.class);
        when(user.isActive()).thenReturn(true);
        when(users.findById(2L)).thenReturn(Optional.of(user));
        when(tokens.findByTokenHash(any())).thenReturn(Optional.of(new RefreshTokenRecord(
                1L, 2L, "hash", "dev-A", now.plusSeconds(60), null, null, null, now)));
        when(tokens.revoke(anyLong(), any())).thenReturn(0);
        var service = new RefreshTokenService(tokens, users, sessions, audit, leaks,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.rotate("test-token", "dev-A"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("最新");
        verifyNoInteractions(sessions, leaks, audit);
        verify(tokens, never()).updateLastUsed(anyLong(), any());
    }
}
