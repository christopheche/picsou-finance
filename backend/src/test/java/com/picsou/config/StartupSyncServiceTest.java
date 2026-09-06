package com.picsou.config;

import com.picsou.service.SchedulerService;
import com.picsou.service.SetupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StartupSyncServiceTest {

    @Mock SchedulerService schedulerService;
    @Mock SetupService setupService;
    @Mock ApplicationArguments args;

    @Test
    void run_replaysTheDailySync() {
        new StartupSyncService(schedulerService).run(args);

        verify(schedulerService).dailyBankSync();
    }

    /** A failing boot sync must never keep the application from starting. */
    @Test
    void run_containsSyncFailures() {
        doThrow(new IllegalStateException("boom")).when(schedulerService).dailyBankSync();

        assertThatCode(() -> new StartupSyncService(schedulerService).run(args)).doesNotThrowAnyException();
    }

    /**
     * The class promises to run after DataSeeder. Spring sorts runners with
     * {@link AnnotationAwareOrderComparator}, under which an unannotated runner sits at
     * LOWEST_PRECEDENCE — i.e. after every annotated one — so the promise only holds while
     * DataSeeder carries an explicit order below this runner's.
     */
    @Test
    void runsAfterDataSeeder() {
        DataSeeder seeder = new DataSeeder(setupService);
        StartupSyncService sync = new StartupSyncService(schedulerService);

        assertThat(AnnotationAwareOrderComparator.INSTANCE.compare(seeder, sync)).isNegative();
    }
}
