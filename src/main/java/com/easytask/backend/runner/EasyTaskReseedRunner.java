package com.easytask.backend.runner;

import com.easytask.backend.organization.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-shot production reseed entry point. Activate with EASYTASK_RESEED=true.
 * DESTRUCTIVE: wipes all organization data, then seeds "Easy Task Org".
 *
 * <p>Guarded so it only runs once: if "Easy Task Org" already exists the reseed
 * is skipped, so leaving the flag on across redeploys does NOT keep wiping.
 * Never active under the test profile.
 */
@Slf4j
@Component
@Order(0)
@Profile("!test")
@ConditionalOnProperty(prefix = "easytask", name = "reseed", havingValue = "true")
@RequiredArgsConstructor
public class EasyTaskReseedRunner implements ApplicationRunner {

    private final EasyTaskReseedInitializer initializer;
    private final OrganizationRepository organizationRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (organizationRepository.existsByNameIgnoreCase(EasyTaskReseedInitializer.ORG_NAME)) {
            log.warn("EasyTaskReseed: '{}' already exists — skipping (remove EASYTASK_RESEED to silence)",
                    EasyTaskReseedInitializer.ORG_NAME);
            return;
        }
        log.warn("EasyTaskReseed: EASYTASK_RESEED=true — WIPING all data and seeding '{}'",
                EasyTaskReseedInitializer.ORG_NAME);
        initializer.reseed();
    }
}
