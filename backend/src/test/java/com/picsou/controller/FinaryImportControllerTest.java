package com.picsou.controller;

import com.picsou.dto.FinaryPreviewResponse;
import com.picsou.service.FinaryImportService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The xlsx preview accepts the same 10 MB upload as every other multipart endpoint — the
 * ceiling in {@code application.yml} is global — and parses it, then caches the result
 * server-side. It shares the sync buckets for the same reason {@code TransactionImportController}
 * does: without one, a member can keep the backend busy with unbounded uploads.
 */
@ExtendWith(MockitoExtension.class)
class FinaryImportControllerTest {

    private static final Long MEMBER_ID = 7L;

    @Mock FinaryImportService service;
    @Mock UserContext userContext;
    @Mock HttpServletRequest request;

    private FinaryImportController controller;
    private ConcurrentHashMap<String, Bucket> syncBuckets;

    @BeforeEach
    void setUp() {
        syncBuckets = new ConcurrentHashMap<>();
        controller = new FinaryImportController(service, userContext, syncBuckets);
        lenient().when(userContext.currentMemberId()).thenReturn(MEMBER_ID);
    }

    @Test
    void preview_forwardsTheFileAndTheResolvedMemberId() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        FinaryPreviewResponse expected = mock(FinaryPreviewResponse.class);
        var file = uploadedFile();
        when(service.preview(file, MEMBER_ID)).thenReturn(expected);

        var response = controller.preview(file, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void eleventhPreviewFromTheSameIpIsRateLimited() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(service.preview(any(), anyLong())).thenReturn(mock(FinaryPreviewResponse.class));
        var file = uploadedFile();

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(controller.preview(file, request).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(controller.preview(file, request).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(service, times(10)).preview(any(), anyLong());
    }

    private static MockMultipartFile uploadedFile() {
        return new MockMultipartFile("file", "finary.xlsx", "application/vnd.ms-excel", new byte[] {1, 2});
    }
}
