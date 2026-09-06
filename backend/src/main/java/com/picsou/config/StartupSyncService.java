package com.picsou.config;

import com.picsou.service.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Triggers initial sync of all accounts at application startup.
 * Runs after DataSeeder to ensure user exists before syncing.
 *
 * <p>The ordering only holds because {@link DataSeeder} carries an explicit {@code @Order(0)}:
 * a runner without the annotation sorts at {@code Ordered.LOWEST_PRECEDENCE}, i.e. <em>after</em>
 * every annotated one, not at 0. A runner that must precede the sync needs an order below 1.
 */
@Component
@Order(1) // After DataSeeder (@Order(0)); before IsinTickerRepairRunner (@Order(2))
public class StartupSyncService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSyncService.class);

    private final SchedulerService schedulerService;

    public StartupSyncService(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting initial sync of all accounts at startup");
        try {
            schedulerService.dailyBankSync();
            log.info("Initial startup sync completed");
        } catch (Exception ex) {
            log.error("Initial startup sync failed", ex);
            // Don't throw — allow the application to start even if sync fails
        }
    }
}
