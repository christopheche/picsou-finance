package com.picsou.config;

import com.picsou.service.AmundiSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AmundiSyncRecoveryTest {

    @Test
    void applicationStartupRecoversPersistedInFlightJobs() {
        AmundiSyncService syncService = mock(AmundiSyncService.class);

        new AmundiSyncRecovery(syncService).run(new DefaultApplicationArguments());

        verify(syncService).recoverInterruptedSyncs();
    }

    @Test
    void runsBeforeStartupSyncQueuesWork() {
        Order recovery = AmundiSyncRecovery.class.getAnnotation(Order.class);
        Order startup = StartupSyncService.class.getAnnotation(Order.class);

        // Interrupted jobs must be failed before StartupSyncService queues new ones,
        // otherwise queueSync sees the stale RUNNING row and returns early.
        assertThat(recovery).isNotNull();
        assertThat(startup).isNotNull();
        assertThat(recovery.value()).isLessThan(startup.value());
    }
}
