package com.budgetowl.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.auth.domain.AuthToken;
import com.budgetowl.auth.domain.AuthTokenSummary;
import com.budgetowl.auth.domain.PasswordCredential;
import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.domain.UserSummary;
import com.budgetowl.household.domain.HouseholdInvitation;
import com.budgetowl.household.domain.HouseholdInvitationSummary;
import com.budgetowl.household.domain.HouseholdMemberSummary;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Password hashes and token hashes cannot be read back out of the persistence layer — <b>by
 * construction, not by annotation</b>.
 *
 * <p>docs/features/authentication-and-households.md asks for "projections that cannot carry them
 * rather than relying on annotations to hide them", and the difference matters: an annotation is
 * something a future contributor has to remember on a class they are writing at the time, and this
 * is something they cannot get wrong because the accessor does not exist.
 *
 * <p>So this test does not inspect annotations. It plants a sentinel value in the secret field by
 * reflection, then invokes <em>every</em> accessible no-argument method on the object and
 * serializes it with Jackson, and asserts the sentinel comes back out of none of them. If someone
 * adds a getter, this fails. If someone makes an entity serializable with its hash, this fails.
 */
class SecretsAreUnreadableTest {

    private static final String SENTINEL = "SENTINEL-SECRET-MUST-NEVER-ESCAPE";

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void aPasswordCredentialNeverGivesItsHashBack() throws Exception {
        assertSecretCannotEscape(PasswordCredential.class, "passwordHash");
    }

    @Test
    void anAuthTokenNeverGivesItsHashBack() throws Exception {
        assertSecretCannotEscape(AuthToken.class, "tokenHash");
    }

    @Test
    void anInvitationNeverGivesItsTokenHashBack() throws Exception {
        assertSecretCannotEscape(HouseholdInvitation.class, "tokenHash");
    }

    @Test
    void theUserEntityHasNoPasswordFieldAtAll() {
        // The strongest form of the guarantee: the entity every service touches has nothing to
        // leak. users.password_hash is mapped by PasswordCredential and by nothing else.
        assertThat(fieldNames(UserAccount.class))
                .as("UserAccount must not learn about password hashes")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("password"))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("hash"));
    }

    @Test
    void aPasswordCredentialStillVerifiesThePasswordItRefusesToShow() {
        // Refusing to expose the hash would be useless if it also refused to do its job.
        PasswordCredential credential = instantiate(PasswordCredential.class);
        set(credential, "passwordHash", SENTINEL);

        assertThat(credential.matches("hunter2", (raw, encoded) -> encoded.equals(SENTINEL)))
                .isTrue();
        assertThat(credential.isSet()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            classes = {
                UserSummary.class,
                AuthTokenSummary.class,
                HouseholdMemberSummary.class,
                HouseholdInvitationSummary.class
            })
    void noProjectionHasAComponentThatCouldCarryASecret(Class<?> projection) {
        // A record with no such component cannot be handed one: a query that tried would not
        // compile, which is the difference between a control and a convention.
        List<String> components =
                java.util.Arrays.stream(projection.getRecordComponents())
                        .map(RecordComponent::getName)
                        .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                        .toList();

        assertThat(components)
                .noneMatch(name -> name.contains("password"))
                .noneMatch(name -> name.contains("hash"))
                .noneMatch(name -> name.contains("secret"))
                .noneMatch(name -> name.equals("token"));
    }

    private void assertSecretCannotEscape(Class<?> type, String secretField) throws Exception {
        Object instance = instantiate(type);
        set(instance, secretField, SENTINEL);

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class) {
                continue;
            }
            Object returned = invokeQuietly(method, instance);
            assertThat(String.valueOf(returned))
                    .as("%s.%s() returned the secret", type.getSimpleName(), method.getName())
                    .doesNotContain(SENTINEL);
        }

        assertThat(instance.toString())
                .as("%s.toString() ends up in logs", type.getSimpleName())
                .doesNotContain(SENTINEL);

        assertThat(serialize(instance))
                .as("%s must not be serializable with its secret", type.getSimpleName())
                .doesNotContain(SENTINEL);
    }

    private String serialize(Object instance) {
        try {
            return json.writeValueAsString(instance);
        } catch (Exception cannotSerialize) {
            // Refusing to serialize at all is a stronger result than serializing without the
            // secret, so this is a pass rather than an error.
            return "";
        }
    }

    private static Object invokeQuietly(Method method, Object instance) {
        try {
            method.setAccessible(true);
            return method.invoke(instance);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // A getter that needs a live persistence context cannot be leaking a hash.
            return null;
        }
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("JPA requires a no-argument constructor on " + type, e);
        }
    }

    private static void set(Object instance, String fieldName, String value) {
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "expected " + instance.getClass() + " to hold its secret in " + fieldName, e);
        }
    }

    private static List<String> fieldNames(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
    }
}
