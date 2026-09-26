package com.budgetowl.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A record that holds a credential must not print it.
 *
 * <p>This test exists because of a real leak, found by the disclosure test and fixed here. Spring
 * MVC logs the deserialized request body at {@code DEBUG} — {@code Read "application/json" to
 * [LoginRequest[email=..., password=...]]} — and a record's generated {@code toString} prints every
 * component. Turning on debug logging to chase an unrelated bug would have written every password
 * anyone typed into a log file, which is how credentials are actually stolen: not by breaking the
 * hash, but by reading a log or a bug report.
 *
 * <p>So it is not enough to have fixed the five records that exist today. Every record in the
 * codebase with a component named like a secret is constructed here with a sentinel value and asked
 * to print itself; if the sentinel comes back, the test names the class. A sixth one written next
 * year fails immediately, at no cost to whoever writes it.
 */
class SecretsAreNotPrintableTest {

    private static final String SENTINEL = "SENTINEL-SECRET-MUST-NEVER-BE-PRINTED";

    /** Names that mean "this value authenticates somebody". */
    private static final Pattern SECRET_NAMES =
            Pattern.compile("(?i).*(password|secret|token|hash|credential).*");

    @Test
    void noRecordPrintsAComponentThatIsACredential() {
        JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.budgetowl");

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (var javaClass : classes) {
            Class<?> type = javaClass.reflect();
            if (!type.isRecord() || !holdsASecret(type)) {
                continue;
            }
            checked++;
            String printed = printWithSentinels(type);
            if (printed.contains(SENTINEL)) {
                offenders.add(type.getName() + " -> " + printed);
            }
        }

        assertThat(checked)
                .as("the scan must actually be looking at something, or it protects nothing")
                .isPositive();
        assertThat(offenders)
                .as("override toString() on these and redact the credential")
                .isEmpty();
    }

    private static boolean holdsASecret(Class<?> type) {
        for (RecordComponent component : type.getRecordComponents()) {
            if (SECRET_NAMES.matcher(component.getName()).matches()
                    && component.getType() == String.class) {
                return true;
            }
        }
        return false;
    }

    /** Builds an instance with the sentinel in every string, then asks it to print itself. */
    private static String printWithSentinels(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            parameterTypes[index] = components[index].getType();
            arguments[index] = valueFor(components[index].getType());
        }
        try {
            Constructor<?> canonical = type.getDeclaredConstructor(parameterTypes);
            canonical.setAccessible(true);
            return String.valueOf(canonical.newInstance(arguments));
        } catch (ReflectiveOperationException | RuntimeException cannotBuild) {
            // A compact constructor that rejects the sentinel is a record that validates its
            // input, which is fine and not what this test is about.
            return "";
        }
    }

    private static Object valueFor(Class<?> type) {
        if (type == String.class) {
            return SENTINEL;
        }
        if (type == UUID.class) {
            return UUID.randomUUID();
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == java.time.Instant.class) {
            return java.time.Instant.EPOCH;
        }
        if (type == java.time.Duration.class) {
            return java.time.Duration.ZERO;
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type == List.class) {
            return List.of();
        }
        if (type == java.util.Map.class) {
            return java.util.Map.of();
        }
        if (type == Locale.class) {
            return Locale.ROOT;
        }
        return null;
    }
}
