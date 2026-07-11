package com.easytask.backend.runner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/demo seeding entry point (Regardian pattern, decision D14).
 * Activate with {@code easytask.seed.demo-data=true} (env: SEED_DEMO_DATA=true).
 * Never active under the test profile. Idempotent: skips when the demo
 * organization already exists.
 */
@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "easytask.seed", name = "demo-data", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private final DemoDataInitializer initializer;

    @Override
    public void run(ApplicationArguments args) {
        log.info("DemoDataSeeder: demo-data flag enabled");
        initializer.initialize();
    }
}
