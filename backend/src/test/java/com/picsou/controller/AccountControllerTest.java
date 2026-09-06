package com.picsou.controller;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.service.AccountConnectionService;
import com.picsou.service.AccountService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito controller test (no Spring context, no MockMvc). Pins the member-scoping contract at
 * this layer: the member handed to the service comes from {@link UserContext}, through the accessor
 * that honours the admin impersonation override ({@code currentMember()} resolves the same member as
 * {@code currentMemberId()}), never from the login directly.
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock AccountService accountService;
    @Mock UserContext userContext;
    @Mock AccountConnectionService accountConnectionService;

    @InjectMocks AccountController controller;

    private static AccountRequest request() {
        return new AccountRequest("Livret A", AccountType.LIVRET_A, null, "EUR",
            new BigDecimal("100"), true, null, null, null, null);
    }

    @Test
    void create_delegatesWithTheContextMember_notTheLoginsOwn() {
        // The context resolves the impersonated profile when an admin has one active; the
        // controller must not go around it via currentUser().getMember().
        FamilyMember impersonated = FamilyMember.builder().id(7L).managed(true).build();
        AccountResponse created = mock(AccountResponse.class);
        when(userContext.currentMember()).thenReturn(impersonated);
        AccountRequest req = request();
        when(accountService.create(same(req), same(impersonated))).thenReturn(created);

        assertThat(controller.create(req)).isSameAs(created);
        verify(userContext, never()).currentUser();
    }

    @Test
    void delete_goesThroughAccountConnectionService_notAccountServiceDelete() {
        when(userContext.currentMemberId()).thenReturn(7L);

        controller.delete(5L);

        verify(accountConnectionService).deleteAccount(5L, 7L);
        verify(accountService, never()).delete(5L, 7L);
    }
}
