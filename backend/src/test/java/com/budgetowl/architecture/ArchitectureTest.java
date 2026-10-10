package com.budgetowl.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.budgetowl.common.HouseholdOwned;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.UUID;

/**
 * Architecture rules (ADR-0024). These make the dangerous shapes unwritable rather than merely
 * reviewable — a reviewer catches most of them most of the time, which is not the same thing.
 *
 * <p>They run as ordinary JUnit tests in milliseconds, and each names the offending class.
 *
 * <p><b>On {@code allowEmptyShould}.</b> The layering rules below are marked to pass when they
 * match nothing, because slice 1 has no feature packages yet. That is a real hazard: a rule
 * checking zero classes is a rule that is not protecting anything, and if a later feature put its
 * controllers in {@code ..controller..} instead of {@code ..web..} these rules would stay silent
 * forever. {@link #every_feature_class_is_in_a_known_layer} is the guard against that — it fails
 * the moment a class appears outside the documented layer packages, so the emptiness can only ever
 * be genuine.
 */
@AnalyzeClasses(packages = "com.budgetowl", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule every_feature_class_is_in_a_known_layer =
            classes()
                    .that()
                    .resideOutsideOfPackages(
                            "com.budgetowl", "com.budgetowl.config..", "com.budgetowl.common..")
                    .should()
                    .resideInAnyPackage("..web..", "..service..", "..domain..", "..persistence..")
                    .allowEmptyShould(true)
                    .because(
                            "the layer rules below match on these package names, so a feature that"
                                    + " invents its own (..controller.., ..repo..) would silently"
                                    + " escape every one of them"
                                    + " (docs/architecture/overview.md)");

    @ArchTest
    static final ArchRule web_never_reaches_persistence =
            noClasses()
                    .that()
                    .resideInAPackage("..web..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.budgetowl..persistence..")
                    .allowEmptyShould(true)
                    .because(
                            "a controller that calls a repository directly skips the layer that"
                                    + " owns authorization (docs/architecture/overview.md)");

    @ArchTest
    static final ArchRule domain_is_plain_java =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.budgetowl..web..",
                            "com.budgetowl..service..",
                            "com.budgetowl..persistence..")
                    .allowEmptyShould(true)
                    .because(
                            "dependencies point inward only. Qualified with com.budgetowl on"
                                    + " purpose: `..persistence..` also matches"
                                    + " `jakarta.persistence`, so the unqualified form failed every"
                                    + " entity the moment the first one was written — and the"
                                    + " overview puts entities in ..domain.. with their JPA"
                                    + " mapping annotations");

    @ArchTest
    static final ArchRule transactional_only_in_service =
            methods()
                    .that()
                    .areAnnotatedWith(
                            org.springframework.transaction.annotation.Transactional.class)
                    .should()
                    .beDeclaredInClassesThat()
                    .resideInAPackage("..service..")
                    .allowEmptyShould(true)
                    .because("services own transactions, not controllers or repositories");

    @ArchTest
    static final ArchRule repositories_expose_no_inherited_unscoped_finder =
            noClasses()
                    .that()
                    .resideInAPackage("..persistence..")
                    .should()
                    .beAssignableTo(org.springframework.data.repository.CrudRepository.class)
                    .orShould()
                    .beAssignableTo(
                            org.springframework.data.repository.PagingAndSortingRepository.class)
                    .allowEmptyShould(true)
                    .because(
                            "CrudRepository brings findById, findAll and deleteAll with it, and a"
                                    + " household-owned findById is the bug ADR-0008 exists to"
                                    + " prevent. Extend the bare Repository marker and write out"
                                    + " the scoped methods (docs/guides/database-style.md)");

    @ArchTest
    static final ArchRule entities_live_in_domain =
            classes()
                    .that()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should()
                    .resideInAPackage("..domain..")
                    .allowEmptyShould(true)
                    .because(
                            "an entity in ..web.. is an entity one step from being a response body"
                                    + " (docs/architecture/overview.md)");

    /**
     * The rule the whole ownership model rests on (ADR-0008 as amended by ADR-0017, non-negotiable
     * #2): household-owned data cannot be reached without naming the household.
     *
     * <p>{@code repositories_expose_no_inherited_unscoped_finder} above stops {@code
     * CrudRepository} smuggling a {@code findById} in. This one stops somebody writing it out by
     * hand — which is the easier mistake, because a hand-written {@code findById(UUID)} looks like
     * perfectly ordinary Spring Data and reads as if the service above it will remember to check.
     * One that forgets returns another household's row, and the service has no way to notice.
     *
     * <p>A method satisfies it by taking a {@code UUID householdId}, or by taking the entity itself
     * — {@code save} and {@code delete} are scoped by the fact that the caller had to get the
     * entity from a scoped finder first.
     *
     * <p>Parameter <em>names</em> are read from the bytecode, which needs {@code -parameters};
     * spring-boot-starter-parent turns it on. If it were ever off this rule fails loudly on correct
     * code rather than passing silently on incorrect code, which is the right direction for a
     * control to break in.
     */
    @ArchTest
    static final ArchRule household_owned_data_is_unreachable_without_its_household =
            methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage("com.budgetowl..persistence..")
                    .and(declaredOnARepositoryForHouseholdOwnedData())
                    .should(takeTheHouseholdIdOrTheEntityItself())
                    .because(
                            "an id from a path or a body is a lookup key and never evidence of"
                                    + " ownership. Another household's account must be"
                                    + " indistinguishable from one that never existed, and the only way"
                                    + " to keep that true at every call site is for the unscoped call"
                                    + " to be unwritable (docs/guides/database-style.md)");

    /**
     * Keeps the rule above honest.
     *
     * <p>It selects on the {@link HouseholdOwned} marker, so a financial entity that forgets the
     * marker would be checked by nothing at all — the same silent-stop-applying failure {@code
     * allowEmptyShould} causes, and the one docs/memory/gotchas.md records. So every entity outside
     * the membership graph has to declare itself.
     */
    @ArchTest
    static final ArchRule financial_entities_declare_the_household_that_owns_them =
            classes()
                    .that()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .and()
                    .resideOutsideOfPackages(
                            // The membership graph that ownership is resolved *from*, plus the
                            // instance's own single configuration row. Everything else is a
                            // household's financial data.
                            "com.budgetowl.auth..",
                            "com.budgetowl.household..",
                            "com.budgetowl.instance..")
                    .should()
                    .implement(HouseholdOwned.class)
                    .because(
                            "every financial table has a direct household_id column (ADR-0017), and"
                                    + " the marker is what makes that enforceable rather than"
                                    + " documented");

    // These two check every class in the codebase, so they are never empty and need no exemption.

    @ArchTest
    static final ArchRule no_double_or_float_fields =
            fields().should()
                    .notHaveRawType(double.class)
                    .andShould()
                    .notHaveRawType(float.class)
                    .andShould()
                    .notHaveRawType(Double.class)
                    .andShould()
                    .notHaveRawType(Float.class)
                    .because(
                            "money is BigDecimal (ADR-0006); a float field near an amount is the"
                                    + " bug this rule exists to make impossible");

    @ArchTest
    static final ArchRule no_double_or_float_returns =
            methods()
                    .should()
                    .notHaveRawReturnType(double.class)
                    .andShould()
                    .notHaveRawReturnType(float.class)
                    .andShould()
                    .notHaveRawReturnType(Double.class)
                    .andShould()
                    .notHaveRawReturnType(Float.class)
                    .because("ADR-0006 — no floating point anywhere near this codebase");

    // ---------------------------------------------------------------------------------- helpers

    private static DescribedPredicate<JavaMethod> declaredOnARepositoryForHouseholdOwnedData() {
        return new DescribedPredicate<>("declared on a repository for household-owned data") {
            @Override
            public boolean test(JavaMethod method) {
                return guardsHouseholdOwnedData(method.getOwner().reflect());
            }
        };
    }

    private static ArchCondition<JavaMethod> takeTheHouseholdIdOrTheEntityItself() {
        return new ArchCondition<>(
                "take a UUID householdId, or the household-owned entity itself") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean scoped = false;
                for (Parameter parameter : method.reflect().getParameters()) {
                    scoped =
                            scoped
                                    || (parameter.getType() == UUID.class
                                            && "householdId".equals(parameter.getName()))
                                    || HouseholdOwned.class.isAssignableFrom(parameter.getType());
                }
                events.add(
                        new SimpleConditionEvent(
                                method,
                                scoped,
                                method.getFullName()
                                        + " reaches household-owned data without a householdId"));
            }
        };
    }

    /**
     * Whether this interface is a Spring Data repository whose aggregate is {@link HouseholdOwned}.
     *
     * <p>Read from the generic interface rather than guessed from the name, so renaming the
     * repository cannot switch the rule off.
     */
    private static boolean guardsHouseholdOwnedData(Class<?> candidate) {
        for (Type implemented : candidate.getGenericInterfaces()) {
            if (implemented instanceof ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> raw
                    && org.springframework.data.repository.Repository.class.isAssignableFrom(raw)) {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    if (argument instanceof Class<?> aggregate
                            && HouseholdOwned.class.isAssignableFrom(aggregate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
