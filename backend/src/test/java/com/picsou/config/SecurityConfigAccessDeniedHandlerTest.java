package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter-level 403 (non-admin on {@code /api/admin/**}) must be a ProblemDetail like
 * every other error, not Boot's {@code {"timestamp","status","error","path"}} body that
 * Spring Security's default {@code sendError(403)} produces.
 */
class SecurityConfigAccessDeniedHandlerTest {

    @Test
    void writesAProblemDetail403_withoutEchoingTheInternalMessage() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/admin/settings/integrations/crypto");
        MockHttpServletResponse res = new MockHttpServletResponse();

        SecurityConfig.PROBLEM_DETAIL_ACCESS_DENIED_HANDLER.handle(
            req, res, new AccessDeniedException("Access Denied"));

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString())
            .contains("\"status\":403")
            .contains("\"title\":\"Forbidden\"")
            .contains("\"detail\":\"You do not have permission to perform this action\"")
            .doesNotContain("timestamp")
            .doesNotContain("path");
    }
}
