package com.picsou.controller;

import com.picsou.dto.ContributionBreakdownResponse;
import com.picsou.dto.FamilyDashboardResponse;
import com.picsou.model.FamilyMember;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.service.FamilyViewService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context, no MockMvc). The viewer handed to the service is
 * {@link UserContext#currentMemberId()} — that id is what the sharing gate in
 * {@code FamilyViewService.getGoalContributions} compares against the goal owner, so it must be the
 * request's member, never a client-supplied one.
 */
@ExtendWith(MockitoExtension.class)
class FamilyViewControllerTest {

    @Mock FamilyViewService familyViewService;
    @Mock FamilyMemberRepository memberRepository;
    @Mock UserContext userContext;

    @InjectMocks FamilyViewController controller;

    @Test
    void getContributions_passesCurrentMemberAsViewer_andTheFullRoster() {
        List<FamilyMember> roster = List.of(
            FamilyMember.builder().id(1L).displayName("Viewer").build(),
            FamilyMember.builder().id(2L).displayName("Owner").build());
        List<ContributionBreakdownResponse> expected =
            List.of(new ContributionBreakdownResponse("Owner", new BigDecimal("100")));
        when(userContext.currentMemberId()).thenReturn(1L);
        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(roster);
        when(familyViewService.getGoalContributions(20L, 1L, roster)).thenReturn(expected);

        assertThat(controller.getContributions(20L)).isSameAs(expected);
    }

    @Test
    void getDashboard_scopesToCurrentMember() {
        FamilyDashboardResponse expected = mock(FamilyDashboardResponse.class);
        when(userContext.currentMemberId()).thenReturn(1L);
        when(familyViewService.getFamilyDashboard(1L)).thenReturn(expected);

        assertThat(controller.getDashboard()).isSameAs(expected);
    }
}
