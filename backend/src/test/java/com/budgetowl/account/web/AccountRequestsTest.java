package com.budgetowl.account.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.service.LastFourChange;
import com.budgetowl.account.web.AccountRequests.CreateAccountRequest;
import com.budgetowl.account.web.AccountRequests.UpdateAccountRequest;
import com.budgetowl.common.web.MoneyJsonModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the edge accepts, and the one thing the edge has to tell the service that JSON cannot say on
 * its own.
 *
 * <p>Fast and Spring-free: the bodies here are validated by a plain {@code Validator} and
 * deserialized by a plain {@code ObjectMapper}, so the whole rejection table costs milliseconds.
 * {@code AccountIT} proves the same values come back as {@code 400 validation-failed} over real
 * HTTP; this proves the table itself.
 */
class AccountRequestsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private final ObjectMapper json = JsonMapper.builder().addModule(new MoneyJsonModule()).build();

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    // ------------------------------------------------------------------------------- lastFour

    /**
     * ADR-0028's whole point: the column cannot grow into a full account number by user behaviour.
     * Unicode digits are in the list because {@code \d} would have accepted them and {@code
     * BigDecimal}-style leniency elsewhere makes that an easy mistake to repeat.
     */
    @ParameterizedTest
    @EmptySource
    @ValueSource(
            strings = {
                "123", "12345", "abcd", "12a4", "١٢٣٤", "+123", " 123", "123 ", " 1234 ", "1234\n",
                "12.4", "-123"
            })
    void rejectsAnythingThatIsNotExactlyFourAsciiDigits(String candidate) {
        assertThat(fieldsRejectedIn(create("Current", candidate, "CHECKING", "GBP", "0")))
                .containsExactly("lastFour");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000", "1234", "9999", "0001"})
    void acceptsFourAsciiDigits(String candidate) {
        assertThat(fieldsRejectedIn(create("Current", candidate, "CHECKING", "GBP", "0")))
                .isEmpty();
    }

    @Test
    void acceptsAnAccountWithNoDigitsAtAll() {
        // Cash in a tin has no number, and that is ordinary rather than unknown.
        assertThat(fieldsRejectedIn(create("Tin", null, "CASH", "GBP", "0"))).isEmpty();
    }

    // ----------------------------------------------------------------------------------- name

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t", "\n", "\r\n   "})
    void rejectsANameWithNothingInIt(String candidate) {
        assertThat(fieldsRejectedIn(create(candidate, null, "CHECKING", "GBP", "0")))
                .contains("name");
    }

    @Test
    void treatsANonBreakingSpaceAsACharacterRatherThanAsBlank() {
        // Not a quirk worth fixing: U+00A0 is not whitespace to Character.isWhitespace, not
        // trimmed by String.strip, and not trimmed by PostgreSQL's btrim either — so Bean
        // Validation, Account.rename and ck_accounts_name_length all agree it is a character.
        // Asserted so that a later "tidy up the trimming" change has to notice all three.
        assertThat(fieldsRejectedIn(create(" ", null, "CHECKING", "GBP", "0"))).isEmpty();
    }

    @Test
    void rejectsANameLongerThanTheColumn() {
        String tooLong = "n".repeat(Account.NAME_MAX_LENGTH + 1);

        assertThat(fieldsRejectedIn(create(tooLong, null, "CHECKING", "GBP", "0")))
                .containsExactly("name");
    }

    @Test
    void acceptsANameOfExactlyTheColumnsLength() {
        String longest = "n".repeat(Account.NAME_MAX_LENGTH);

        assertThat(fieldsRejectedIn(create(longest, null, "CHECKING", "GBP", "0"))).isEmpty();
    }

    @Test
    void boundsTheRawNameAndNotOnlyTheTrimmedOne() {
        // `length(btrim(name)) <= 80` on its own is satisfied by any amount of padding, which is
        // the gap V8 closed on every other user-supplied text column.
        String padded = " ".repeat(200) + "Current" + " ".repeat(200);

        assertThat(fieldsRejectedIn(create(padded, null, "CHECKING", "GBP", "0")))
                .containsExactly("name");
    }

    // -------------------------------------------------------------------------- openingBalance

    @ParameterizedTest
    @EmptySource
    @ValueSource(
            strings = {
                "abc",
                "1.23456",
                "1e5",
                "+1",
                "1,5",
                "1.",
                ".5",
                "- 1",
                "1 ",
                "١٢٣٤",
                "1234567890123456",
                "Infinity",
                "NaN"
            })
    void rejectsAnAmountThatIsNotASignedDecimalFittingTheColumn(String candidate) {
        assertThat(fieldsRejectedIn(create("Current", null, "CHECKING", "GBP", candidate)))
                .containsExactly("openingBalance");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "0.0000",
                "-0",
                "-400.00",
                "-1234.5678",
                "999999999999999.9999",
                "0.0001"
            })
    void acceptsASignedAmountTheColumnCanHold(String candidate) {
        // Negative is normal for a credit card and zero is valid and common for a new account:
        // neither is warned about and neither is treated as "unset".
        assertThat(fieldsRejectedIn(create("Current", null, "CHECKING", "GBP", candidate)))
                .isEmpty();
    }

    // ------------------------------------------------------------------------------- currency

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"GB", "GBPX", "G8P", "£££", "gb"})
    void rejectsACurrencyOfTheWrongShape(String candidate) {
        assertThat(fieldsRejectedIn(create("Current", null, "CHECKING", candidate, "0")))
                .contains("currency");
    }

    @Test
    void leavesWhetherACurrencyExistsToTheService() {
        // "ZZZ" is three uppercase letters and is not a currency. The shape is the edge's job;
        // the ISO-4217 table is checked in the service, which is what makes the answer a
        // validation-failed naming the field rather than a 500 several frames later.
        assertThat(fieldsRejectedIn(create("Current", null, "CHECKING", "ZZZ", "0"))).isEmpty();
    }

    // ----------------------------------------------------------------------------- the type

    @Test
    void requiresATypeButDoesNotEnumerateThemHere() {
        assertThat(fieldsRejectedIn(create("Current", null, " ", "GBP", "0")))
                .containsExactly("type");
        assertThat(fieldsRejectedIn(create("Current", null, "SPACESHIP", "GBP", "0"))).isEmpty();
    }

    // ----------------------------------------------------- the three-way answer on an update

    @Test
    void readsAnAbsentLastFourAsUnchanged() {
        UpdateAccountRequest body = update("{\"version\":0}");

        assertThat(body.lastFourChange()).isInstanceOf(LastFourChange.Unchanged.class);
    }

    @Test
    void readsAnExplicitNullLastFourAsCleared() {
        // The one request that cannot be expressed if absent and null are collapsed: undoing a
        // mistyped number (ADR-0028).
        UpdateAccountRequest body = update("{\"version\":0,\"lastFour\":null}");

        assertThat(body.lastFourChange()).isInstanceOf(LastFourChange.Cleared.class);
    }

    @Test
    void readsAGivenLastFourAsASetValue() {
        UpdateAccountRequest body = update("{\"version\":0,\"lastFour\":\"4412\"}");

        assertThat(body.lastFourChange()).isEqualTo(LastFourChange.setTo("4412"));
    }

    @Test
    void requiresTheVersionTheCallerReadOnAnUpdate() {
        assertThat(fieldsRejectedIn(update("{\"name\":\"Renamed\"}"))).containsExactly("version");
    }

    @Test
    void acceptsAnUpdateThatMentionsOneFieldAndNothingElse() {
        assertThat(fieldsRejectedIn(update("{\"version\":3,\"name\":\"Renamed\"}"))).isEmpty();
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\n"})
    void rejectsABlankNameOnAnUpdateToo(String candidate) {
        UpdateAccountRequest body =
                update("{\"version\":0,\"name\":\"" + candidate.replace("\n", "\\n") + "\"}");

        assertThat(fieldsRejectedIn(body)).containsExactly("name");
    }

    @Test
    void keepsTheDigitsOutOfTheLoggedFormOfEveryRequestBody() {
        // Spring MVC logs the deserialized body at DEBUG, and a record's generated toString would
        // print every component (ADR-0028, and the bug slice 2's disclosure test caught).
        assertThat(create("Chase", "4412", "CHECKING", "GBP", "-1234.5678").toString())
                .doesNotContain("4412")
                .doesNotContain("Chase")
                .doesNotContain("1234.5678");
        assertThat(update("{\"version\":7,\"lastFour\":\"4412\",\"name\":\"Chase\"}").toString())
                .doesNotContain("4412")
                .doesNotContain("Chase");
    }

    // ---------------------------------------------------------------------------------- helpers

    private static CreateAccountRequest create(
            String name, String lastFour, String type, String currency, String openingBalance) {
        return new CreateAccountRequest(name, lastFour, type, currency, openingBalance);
    }

    private UpdateAccountRequest update(String body) {
        return json.readValue(body, UpdateAccountRequest.class);
    }

    private static Set<String> fieldsRejectedIn(Object body) {
        return validator.validate(body).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
    }
}
