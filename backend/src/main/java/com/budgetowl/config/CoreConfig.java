package com.budgetowl.config;

import com.budgetowl.common.web.MoneyJsonModule;
import java.time.Clock;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/** Beans that are not about security but are needed everywhere. */
@Configuration
public class CoreConfig {

    /**
     * Amounts are strings on the wire, never JSON numbers (ADR-0006). Registered as a bean so it
     * applies to <em>every</em> {@code ObjectMapper} Spring Boot builds, rather than to the one
     * mapper somebody remembered to configure.
     */
    @Bean
    MoneyJsonModule moneyJsonModule() {
        return new MoneyJsonModule();
    }

    /**
     * A JSON number is never accepted where this API expects text.
     *
     * <p>This closes the hole {@link MoneyJsonModule} cannot reach. Amounts are declared as {@code
     * String} on request DTOs so that a malformed one is a {@code validation-failed} naming the
     * field — but Jackson will happily coerce {@code {"openingBalance": 1234.56}} into the string
     * {@code "1234.56"}, and the module's deserializer never runs. The digits in that request are
     * already whatever the client's double rounded to (docs/memory/gotchas.md), so accepting them
     * silently is exactly the failure ADR-0006 exists to prevent — and an integration test proved
     * it was a {@code 201} before this bean existed.
     *
     * <p>Applied to every textual target rather than only to amounts, because a client sending a
     * number where a name belongs is also a mistake worth answering with a {@code 400}: {@code
     * fail-on-unknown-properties} already says a typo is an error rather than a guess, and this is
     * the same principle one level down.
     */
    @Bean
    JsonMapperBuilderCustomizer refuseCoercionIntoText() {
        return builder ->
                builder.withCoercionConfig(
                                LogicalType.Textual,
                                coercion -> {
                                    coercion.setCoercion(
                                            CoercionInputShape.Integer, CoercionAction.Fail);
                                    coercion.setCoercion(
                                            CoercionInputShape.Float, CoercionAction.Fail);
                                    coercion.setCoercion(
                                            CoercionInputShape.Boolean, CoercionAction.Fail);
                                })
                        // An enum bound from a JSON integer is its ORDINAL, so {"role":0} was a
                        // silent OWNER and {"role":2} a silent VIEWER. Both routes that take a
                        // role are OWNER-only, so this was never privilege escalation by an
                        // outsider — the realistic failure is a client sending a default-
                        // initialised int or a select's index and creating an OWNER invitation
                        // where it meant VIEWER. Role is the highest-value authorization control
                        // this app has (security-model.md), so a typo must not grant it.
                        .withCoercionConfig(
                                LogicalType.Enum,
                                coercion -> {
                                    coercion.setCoercion(
                                            CoercionInputShape.Integer, CoercionAction.Fail);
                                    coercion.setCoercion(
                                            CoercionInputShape.Float, CoercionAction.Fail);
                                    coercion.setCoercion(
                                            CoercionInputShape.Boolean, CoercionAction.Fail);
                                });
    }

    /**
     * Injected rather than called statically, so a test can fix time instead of waiting for it —
     * and so nothing in production calls {@code Instant.now()} at a point a test cannot reach
     * (docs/guides/testing-style.md).
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
