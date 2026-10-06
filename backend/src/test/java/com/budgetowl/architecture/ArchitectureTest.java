package com.budgetowl.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
}
