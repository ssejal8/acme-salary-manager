package com.acme.salary.config;

import com.acme.salary.common.money.Money;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Serialises every {@link BigDecimal} as a JSON <em>string</em> at scale 2.
 *
 * <p>Amounts must not reach a JavaScript client as JSON numbers: parsing them into an
 * IEEE-754 double is exactly the precision loss ADR-006 exists to prevent. Sending strings
 * makes accidental client-side arithmetic obvious rather than silently wrong
 * (architecture §6.3). Only money uses {@code BigDecimal} in this codebase, so applying
 * this globally is safe — ids and counts are integral types.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public SimpleModule moneyModule() {
        SimpleModule module = new SimpleModule("acme-money");
        module.addSerializer(BigDecimal.class, new PlainScaledDecimalSerializer());
        return module;
    }

    private static final class PlainScaledDecimalSerializer extends JsonSerializer<BigDecimal> {

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(Money.normalize(value).toPlainString());
        }
    }
}
