package com.picsou.controller;

import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.service.TradeRepublicSyncService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The TR entry points had neither request validation nor a throttle on anything but
 * {@code /auth/initiate}. Both gaps are pinned here:
 *
 * <ul>
 *   <li>a body missing a field reached {@code TradeRepublicAdapter}, whose {@code Map.of(...)}
 *       rejects nulls with an NPE — a 500 where the feature note promises a 422;</li>
 *   <li>{@code /auth/complete} verified a 4-digit TAN with no bucket, {@code /sync} drove a
 *       sidecar round-trip with no bucket, and {@code /import} parsed a 10 MB upload with none
 *       either.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TradeRepublicControllerTest {

    private static final Long MEMBER_ID = 7L;

    @Mock TradeRepublicSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private TradeRepublicController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private ConcurrentHashMap<String, Bucket> tanBuckets;
    private ConcurrentHashMap<String, Bucket> syncBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        tanBuckets = new ConcurrentHashMap<>();
        syncBuckets = new ConcurrentHashMap<>();
        controller = new TradeRepublicController(service, userContext, authBuckets, tanBuckets, syncBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    // ─── Request validation ──────────────────────────────────────────────────

    @Test
    void initiateWithoutPin_is422_andNeverReachesTheAdapter() throws Exception {
        mockMvc.perform(post("/api/tr/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+33612345678\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.pin").exists());

        verify(service, never()).initiateAuth(anyString(), anyString());
    }

    @Test
    void completeWithoutTan_is422_andNeverReachesTheAdapter() throws Exception {
        mockMvc.perform(post("/api/tr/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processId\":\"process-1\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.tan").exists());

        verify(service, never()).completeAuth(anyString(), anyString(), anyLong());
    }

    @Test
    void oversizedPhoneNumber_is422() throws Exception {
        mockMvc.perform(post("/api/tr/auth/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + "9".repeat(101) + "\",\"pin\":\"1234\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.phoneNumber").exists());
    }

    @Test
    void completeScopesTheTanToTheCurrentMember() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        var expected = new TradeRepublicSyncService.SessionStatusResponse(true, Instant.EPOCH);
        when(service.completeAuth("process-1", "1234", MEMBER_ID)).thenReturn(expected);

        var response = controller.completeAuth(
            new TradeRepublicController.CompleteAuthRequest("process-1", "1234"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    // ─── Rate limiting ───────────────────────────────────────────────────────

    /** The TAN space is only 10,000: an unthrottled verify endpoint is brute-forceable. */
    @Test
    void sixthTanVerificationFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.completeAuth(anyString(), anyString(), anyLong()))
            .thenReturn(new TradeRepublicSyncService.SessionStatusResponse(true, Instant.EPOCH));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(controller.completeAuth(
                new TradeRepublicController.CompleteAuthRequest("process-1", "1234"), request
            ).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.completeAuth(
            new TradeRepublicController.CompleteAuthRequest("process-1", "1234"), request
        ).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(5)).completeAuth(anyString(), anyString(), anyLong());
    }

    /**
     * Verification runs on its own budget, not the SMS one: {@code /auth/initiate} allows only
     * 3 per 10 minutes because each sends an SMS, and sharing that would lock a member out of
     * their own login after one mistyped code.
     */
    @Test
    void tanVerificationDoesNotSpendTheSmsBudget() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.completeAuth(anyString(), anyString(), anyLong()))
            .thenReturn(new TradeRepublicSyncService.SessionStatusResponse(true, Instant.EPOCH));

        controller.completeAuth(new TradeRepublicController.CompleteAuthRequest("p", "1234"), request);

        assertThat(tanBuckets).containsOnlyKeys("127.0.0.1");
        assertThat(authBuckets).isEmpty();
    }

    @Test
    void rateLimitKeysOnTheTrustedProxyClientIp() {
        var proxiedRequest = new MockHttpServletRequest();
        proxiedRequest.setRemoteAddr("172.18.0.2");
        proxiedRequest.addHeader("X-Forwarded-For", "1.2.3.4");
        proxiedRequest.addHeader("X-Real-IP", "203.0.113.9");
        when(service.sync(MEMBER_ID)).thenReturn(List.of());

        controller.sync(proxiedRequest);

        assertThat(syncBuckets).containsOnlyKeys("203.0.113.9");
    }

    /** Every sync decrypts the stored session and drives a sidecar round-trip with a token refresh. */
    @Test
    void eleventhSyncFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.sync(MEMBER_ID)).thenReturn(List.of());

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(controller.sync(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.sync(request).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(10)).sync(MEMBER_ID);
    }

    /** The 10 MB multipart ceiling is global; only a bucket bounds repeated uploads. */
    @Test
    void csvImportSharesTheSyncBudgetAndIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.importCsv(any(), anyLong())).thenReturn(List.of());
        var file = new MockMultipartFile("file", "tr.csv", "text/csv", "a,b\n".getBytes());

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(controller.importCsv(file, request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.importCsv(file, request).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(10)).importCsv(any(), anyLong());
    }
}
