package com.budgetowl.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.and;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Locale;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporting's two structural promises (docs/features/reporting.md, attack 8 and "Read-only by
 * construction"), made unwritable rather than reviewable (ADR-0024).
 *
 * <ol>
 *   <li><b>Nothing in the reporting package can make an outbound call.</b> Every insight is server
 *       arithmetic over the ledger. An insight computed by sending a household's figures to an
 *       outside service would put its spending profile in someone else's logs, against ADR-0016 and
 *       ADR-0027 — and adding an HTTP client here fails the build rather than a review.
 *   <li><b>Nothing in the reporting package can write.</b> It reaches no repository but its own,
 *       its own declares nothing but {@code select} queries, and every service method runs in a
 *       read-only transaction.
 * </ol>
 */
@AnalyzeClasses(packages = "com.budgetowl", importOptions = ImportOption.DoNotIncludeTests.class)
class ReportingArchitectureTest {

    private static final String REPORTING = "com.budgetowl.report..";

    @ArchTest
    static final ArchRule reporting_depends_on_no_http_client =
            noClasses()
                    .that()
                    .resideInAPackage(REPORTING)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "java.net.http..",
                            "org.springframework.web.client..",
                            "org.springframework.web.reactive..",
                            "org.springframework.web.service..",
                            "org.springframework.http.client..",
                            "org.apache.hc..",
                            "org.apache.http..",
                            "okhttp3..",
                            "javax.net..")
                    .orShould()
                    .dependOnClassesThat()
                    .areAssignableTo(java.net.URLConnection.class)
                    .orShould()
                    .dependOnClassesThat()
                    .areAssignableTo(java.net.Socket.class)
                    .orShould()
                    .dependOnClassesThat()
                    .belongToAnyOf(java.net.URL.class)
                    .because(
                            "an insight is arithmetic over the ledger, never a call to anything"
                                    + " outside the instance (ADR-0016, ADR-0027,"
                                    + " docs/features/reporting.md attack 8)");

    @ArchTest
    static final ArchRule reporting_reaches_no_repository_but_its_own =
            noClasses()
                    .that()
                    .resideInAPackage(REPORTING)
                    .should()
                    .dependOnClassesThat(
                            and(
                                    resideInAPackage("com.budgetowl..persistence.."),
                                    resideOutsideOfPackage("com.budgetowl.report.persistence..")))
                    .because(
                            "another feature's repository brings its save and delete with it; the"
                                    + " report repository declares only selects");

    @ArchTest
    static final ArchRule the_report_repository_only_selects =
            methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage("com.budgetowl.report.persistence..")
                    .and()
                    .areDeclaredInClassesThat()
                    .areInterfaces()
                    .should()
                    .notBeAnnotatedWith(Modifying.class)
                    .andShould(beASelectQuery())
                    .because("reports are read-only by construction");

    @ArchTest
    static final ArchRule every_report_service_method_is_a_read_only_transaction =
            methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideInAPackage("com.budgetowl.report.service..")
                    .and()
                    .arePublic()
                    .and()
                    .areDeclaredInClassesThat()
                    .haveSimpleNameEndingWith("Service")
                    .should(beAReadOnlyTransaction())
                    .because("a read-only transaction is what a report is, and documents it");

    private static ArchCondition<JavaMethod> beASelectQuery() {
        return new ArchCondition<>("be a @Query whose text is a select") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Query query = method.reflect().getAnnotation(Query.class);
                boolean select =
                        query != null
                                && query.value()
                                        .strip()
                                        .toLowerCase(Locale.ROOT)
                                        .startsWith("select")
                                && !query.nativeQuery();
                events.add(
                        new SimpleConditionEvent(
                                method,
                                select,
                                method.getFullName()
                                        + " is not a JPQL select — a derived or native query can"
                                        + " write, or hide that it does"));
            }
        };
    }

    private static ArchCondition<JavaMethod> beAReadOnlyTransaction() {
        return new ArchCondition<>("be @Transactional(readOnly = true)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (isRecordAccessorOrObjectMethod(method)) {
                    return;
                }
                Transactional transactional = method.reflect().getAnnotation(Transactional.class);
                boolean readOnly = transactional != null && transactional.readOnly();
                events.add(
                        new SimpleConditionEvent(
                                method,
                                readOnly,
                                method.getFullName() + " is not a read-only transaction"));
            }
        };
    }

    private static boolean isRecordAccessorOrObjectMethod(JavaMethod method) {
        JavaClass owner = method.getOwner();
        return owner.isRecord()
                || method.getName().equals("toString")
                || method.getName().equals("equals")
                || method.getName().equals("hashCode");
    }
}
