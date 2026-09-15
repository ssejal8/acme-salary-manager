package com.acme.salary.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A fixed {@link Clock} for slice tests, which do not load the application's
 * {@code ClockConfig}.
 *
 * <p>Fixed rather than system: audit timestamps and period boundaries are then
 * deterministic, which is the whole reason the production code injects a clock instead of
 * calling {@code Instant.now()} (architecture §9).
 */
@TestConfiguration
public class ClockTestConfig {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-09-16T09:00:00Z");

    @Bean
    public Clock clock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }
}
