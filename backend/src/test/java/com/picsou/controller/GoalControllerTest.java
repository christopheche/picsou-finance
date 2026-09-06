package com.picsou.controller;

import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.GoalRequest;
import com.picsou.model.FamilyMember;
import com.picsou.service.GoalService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context, no MockMvc). Pins the member-scoping contract at
 * this layer: the member handed to the service comes from {@link UserContext}, through the accessor
 * that honours the admin impersonation override — a goal created while impersonating a managed
 * profile attaches that profile's accounts and lands under that profile.
 */
@ExtendWith(MockitoExtension.class)
class GoalControllerTest {

    @Mock GoalService goalService;
    @Mock UserContext userContext;

    @InjectMocks GoalController controller;

    @Test
    void create_delegatesWithTheContextMember_notTheLoginsOwn() {
        FamilyMember impersonated = FamilyMember.builder().id(7L).managed(true).build();
        GoalProgressResponse created = mock(GoalProgressResponse.class);
        GoalRequest req = new GoalRequest("House", new BigDecimal("50000"),
            LocalDate.now().plusYears(2), List.of(42L));
        when(userContext.currentMember()).thenReturn(impersonated);
        when(goalService.create(same(req), same(impersonated))).thenReturn(created);

        assertThat(controller.create(req)).isSameAs(created);
        verify(userContext, never()).currentUser();
    }

    @Test
    void update_scopesToTheContextMemberId() {
        GoalProgressResponse updated = mock(GoalProgressResponse.class);
        GoalRequest req = new GoalRequest("House", new BigDecimal("50000"),
            LocalDate.now().plusYears(2), List.of(42L));
        when(userContext.currentMemberId()).thenReturn(7L);
        when(goalService.update(5L, req, 7L)).thenReturn(updated);

        assertThat(controller.update(5L, req)).isSameAs(updated);
    }
}
