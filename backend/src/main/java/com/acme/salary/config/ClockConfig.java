package com.acme.salary.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the application's {@link Clock}.
 *
 * <p>Business code injects this rather than calling {@code LocalDate.now()} inline
 * (architecture §9). Period boundaries, effective-from dates and token expiry all depend
 * on "now", and a test that cannot control "now" cannot test a month boundary.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
