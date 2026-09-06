package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.FinaryImportRequest;
import com.picsou.dto.FinaryImportResultResponse;
import com.picsou.service.FinaryImportService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Finary xlsx file import (two-phase: preview + import).
 *
 * <p>{@code /preview} is IP-throttled with the shared sync buckets, like every other upload
 * endpoint: the 10 MB multipart ceiling in {@code application.yml} is global, and each preview
 * parses the workbook and caches the result server-side, so without a bucket a member can keep
 * the backend busy with unbounded uploads.
 */
@RestController
@RequestMapping("/api/finary")
public class FinaryImportController {

    private final FinaryImportService finaryImportService;
    private final UserContext userContext;
    private final Map<String, Bucket> syncBuckets;

    public FinaryImportController(
        FinaryImportService finaryImportService,
        UserContext userContext,
        @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets
    ) {
        this.finaryImportService = finaryImportService;
        this.userContext = userContext;
        this.syncBuckets = syncBuckets;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if (!checkRateLimit(request)) {
            return tooManyRequests();
        }
        return ResponseEntity.ok(finaryImportService.preview(file, userContext.currentMemberId()));
    }

    @PostMapping("/import")
    public FinaryImportResultResponse importData(@RequestBody FinaryImportRequest request) {
        return finaryImportService.executeImport(request, userContext.currentMemberId());
    }

    private boolean checkRateLimit(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = syncBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createSyncBucket());
        return bucket.tryConsume(1);
    }

    private static ResponseEntity<ProblemDetail> tooManyRequests() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setDetail("Too many import requests. Please wait a moment.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
    }
}
