package com.picsou.controller;

import com.picsou.dto.FinaryApiSyncPreviewRequest;
import com.picsou.dto.FinaryAutoSyncResponse;
import com.picsou.dto.FinaryCheckTotpResponse;
import com.picsou.dto.FinaryLoginRequest;
import com.picsou.dto.FinaryPreviewResponse;
import com.picsou.exception.GlobalExceptionHandler;
import com.picsou.finary.FinaryApiSyncService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Three properties of the Finary entry points are pinned here:
 *
 * <ul>
 *   <li>{@code /login} validates its body — both fields land in {@code NOT NULL}
 *       {@code varchar(500)} columns after encryption, so a missing one used to be a 500;</li>
 *   <li>every endpoint that runs a Clerk sign-in is throttled — an unthrottled loop is a
 *       brute-force oracle on the 6-digit second factor and gets the instance's IP blocked;</li>
 *   <li>the second factor is read from the JSON body, never from {@code ?totp=}, so it cannot
 *       land in reverse-proxy access logs.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FinaryApiSyncControllerTest {

    private static final Long MEMBER_ID = 7L;

    @Mock FinaryApiSyncService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private FinaryApiSyncController controller;
    private ConcurrentHashMap<String, Bucket> authBuckets;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authBuckets = new ConcurrentHashMap<>();
        controller = new FinaryApiSyncController(service, userContext, authBuckets);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    // ─── Request validation ──────────────────────────────────────────────────

    @Test
    void loginWithoutPassword_is422_andStoresNothing() throws Exception {
        mockMvc.perform(post("/api/finary/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.password").exists());

        verify(service, never()).login(anyString(), anyString(), anyLong());
    }

    @Test
    void login_forwardsCredentialsAndTheResolvedMemberId() {
        controller.login(new FinaryLoginRequest("user@example.com", "hunter2"));

        verify(service).login("user@example.com", "hunter2", MEMBER_ID);
    }

    // ─── TOTP travels in the body ────────────────────────────────────────────

    @Test
    void preview_readsTheSecondFactorFromTheBody() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        FinaryPreviewResponse expected = mock(FinaryPreviewResponse.class);
        when(service.preview("123456", MEMBER_ID)).thenReturn(expected);

        var response = controller.apiSyncPreview(new FinaryApiSyncPreviewRequest("123456"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    /** The first attempt carries no second factor at all, so the body is optional. */
    @Test
    void preview_withoutABody_passesNoTotp() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        FinaryPreviewResponse expected = mock(FinaryPreviewResponse.class);
        when(service.preview(isNull(), eq(MEMBER_ID))).thenReturn(expected);

        var response = controller.apiSyncPreview(null, request);

        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void preview_rejectsAMalformedTotp() throws Exception {
        mockMvc.perform(post("/api/finary/api-sync/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"12ab\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.totp").exists());

        verify(service, never()).preview(anyString(), anyLong());
    }

    // ─── Rate limiting ───────────────────────────────────────────────────────

    @Test
    void sixthPreviewFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.preview(isNull(), eq(MEMBER_ID))).thenReturn(mock(FinaryPreviewResponse.class));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(controller.apiSyncPreview(null, request).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.apiSyncPreview(null, request).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(5)).preview(isNull(), anyLong());
    }

    /** One shared Finary budget: check-totp, preview and auto-sync all sign in to Clerk. */
    @Test
    void checkTotpPreviewAndAutoSyncShareOneBudget() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.checkTotp(MEMBER_ID)).thenReturn(mock(FinaryCheckTotpResponse.class));
        when(service.preview(isNull(), eq(MEMBER_ID))).thenReturn(mock(FinaryPreviewResponse.class));
        when(service.autoSync(MEMBER_ID)).thenReturn(mock(FinaryAutoSyncResponse.class));

        assertThat(controller.checkTotp(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.apiSyncPreview(null, request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.apiSyncAuto(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.checkTotp(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.apiSyncAuto(request).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(controller.checkTotp(request).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(authBuckets).containsOnlyKeys("127.0.0.1");
    }
}
