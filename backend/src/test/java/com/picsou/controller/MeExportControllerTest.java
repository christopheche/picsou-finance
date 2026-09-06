package com.picsou.controller;

import com.picsou.dto.ExportRequest;
import com.picsou.dto.ReAuthDto;
import com.picsou.export.DataExportService;
import com.picsou.export.ExportContext;
import com.picsou.model.AppUser;
import com.picsou.service.ReAuthService;
import com.picsou.service.ReAuthService.ReAuthFailedException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context, no MockMvc) — mirrors {@code AccessKeyControllerTest}.
 *
 * <p>Pins the step-up contract in front of the GDPR archive, which carries the MFA secret, recovery
 * codes and provider session ciphertexts: a failed re-authentication propagates <em>before</em> the
 * streaming body is built, so {@link DataExportService} is never reached; the per-user rate limit is
 * consumed before re-auth so that guessing a password or TOTP costs a slot; and the happy path streams
 * the authenticated {@link AppUser} (never a {@code ?memberId=} overlay) with the requested options.
 */
@ExtendWith(MockitoExtension.class)
class MeExportControllerTest {

    private static final long USER_ID = 7L;

    @Mock DataExportService dataExportService;
    @Mock ReAuthService reAuthService;
    @Mock HttpServletRequest httpRequest;

    private Map<String, Bucket> exportBuckets;
    private MeExportController controller;
    private AppUser user;

    @BeforeEach
    void setUp() {
        exportBuckets = new ConcurrentHashMap<>();
        controller = new MeExportController(dataExportService, reAuthService, exportBuckets);
        user = AppUser.builder().id(USER_ID).username("alice").build();
    }

    @Test
    void export_reauthFailure_propagates401_andStreamsNothing() {
        ReAuthDto reAuth = new ReAuthDto("wrong", null);
        doThrow(new ReAuthFailedException("Your password is incorrect."))
            .when(reAuthService).verify(user, reAuth);

        assertThatThrownBy(() -> controller.export(user, new ExportRequest(reAuth, true), httpRequest))
            .isInstanceOf(ReAuthFailedException.class);

        verifyNoInteractions(dataExportService);
    }

    @Test
    void export_rateLimited_returns429_withoutConsultingReAuth() {
        Bucket drained = Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(1).refillIntervally(1, Duration.ofHours(1)).build())
            .build();
        drained.tryConsume(1);
        exportBuckets.put(String.valueOf(USER_ID), drained);

        ResponseEntity<StreamingResponseBody> response =
            controller.export(user, new ExportRequest(new ReAuthDto("secret", null), false), httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(reAuthService, dataExportService);
    }

    /** The slot is spent even when re-auth then fails, so a wrong guess cannot be retried for free. */
    @Test
    void export_consumesRateLimitSlot_beforeReAuth() {
        doThrow(new ReAuthFailedException("The verification code is incorrect."))
            .when(reAuthService).verify(eq(user), any());

        assertThatThrownBy(() -> controller.export(user,
            new ExportRequest(new ReAuthDto(null, "000000"), false), httpRequest))
            .isInstanceOf(ReAuthFailedException.class);

        assertThat(exportBuckets).containsKey(String.valueOf(USER_ID));
        assertThat(exportBuckets.get(String.valueOf(USER_ID)).getAvailableTokens()).isEqualTo(4L);
    }

    @Test
    void export_happyPath_streamsAuthenticatedUser_andSetsContentDisposition() throws Exception {
        ReAuthDto reAuth = new ReAuthDto("secret", null);
        ExportRequest request = new ExportRequest(reAuth, true);

        ResponseEntity<StreamingResponseBody> response = controller.export(user, request, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).hasToString("application/zip");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .matches("attachment; filename=\"picsou-export-alice-\\d{8}-\\d{6}\\.zip\"");
        verify(reAuthService).verify(user, reAuth);

        // The body is lazy: nothing is exported until Spring pulls the stream.
        verifyNoInteractions(dataExportService);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        verify(dataExportService).export(same(user), eq(new ExportContext(true)), same(out));
    }
}
