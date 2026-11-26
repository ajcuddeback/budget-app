package com.budgetowl.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.budgetowl.checkin.persistence.CheckInLedgerRepository;
import com.budgetowl.transaction.domain.Transaction;
import com.budgetowl.transaction.service.TransactionService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * What the weekly check-in can never do, as rules that fail the build (threat model, attack 14;
 * docs/features/weekly-check-in.md, "What a check-in never does").
 *
 * <ol>
 *   <li><b>It contacts nothing off the instance.</b> No HTTP client, the reporting precedent.
 *   <li><b>It never creates, deletes, pairs or splits a transaction or a transfer</b>, and never
 *       files one into an envelope. It reaches no transaction, transfer, import or envelope service
 *       or repository, and of {@link TransactionService} it calls {@code recategorise} and nothing
 *       else.
 *   <li><b>It calls no mutator on a transaction entity</b>: the rows it locks are read, and the one
 *       change it makes goes through the owning service.
 *   <li><b>Its ledger repository only selects.</b>
 * </ol>
 */
@AnalyzeClasses(packages = "com.budgetowl", importOptions = ImportOption.DoNotIncludeTests.class)
class CheckInArchitectureTest {

    private static final String CHECK_IN = "com.budgetowl.checkin..";

    /** The only transaction write the check-in may make. */
    private static final Set<String> ALLOWED_TRANSACTION_SERVICE_CALLS = Set.of("recategorise");

    /** Read accessors on the entity; everything else on it writes. */
    private static final Set<String> TRANSACTION_READS =
            Set.of(
                    "id",
                    "householdId",
                    "accountId",
                    "date",
                    "amount",
                    "payeeId",
                    "categoryId",
                    "note",
                    "status",
                    "externalId",
                    "transferGroupId",
                    "importBatchId",
                    "isTransfer",
                    "createdAt",
                    "updatedAt",
                    "version",
                    "toString",
                    "hashCode",
                    "equals");

    @ArchTest
    static final ArchRule the_check_in_depends_on_no_http_client =
            noClasses()
                    .that()
                    .resideInAPackage(CHECK_IN)
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
                    .because(
                            "a check-in is arithmetic over the household's own records and never"
                                    + " contacts a bank, a provider or anything off the instance"
                                    + " (ADR-0016)");

    @ArchTest
    static final ArchRule the_check_in_reaches_no_ledger_writer =
            noClasses()
                    .that()
                    .resideInAPackage(CHECK_IN)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.budgetowl.transaction.persistence..",
                            "com.budgetowl.transfer..",
                            "com.budgetowl.imports..",
                            "com.budgetowl.envelope.service..",
                            "com.budgetowl.envelope.persistence..",
                            "com.budgetowl.account.service..")
                    .because(
                            "a check-in never creates, deletes, pairs or splits a transaction,"
                                    + " never touches a transfer, and never files a row into an"
                                    + " envelope; those paths must not even be reachable from it");

    @ArchTest
    static final ArchRule the_check_in_calls_only_recategorise_on_the_transaction_service =
            noClasses()
                    .that()
                    .resideInAPackage(CHECK_IN)
                    .should()
                    .callMethodWhere(
                            onTransactionServiceOtherThan(ALLOWED_TRANSACTION_SERVICE_CALLS))
                    .because(
                            "the only change a check-in makes to a transaction is its category"
                                    + " (docs/features/weekly-check-in.md)");

    @ArchTest
    static final ArchRule the_check_in_calls_no_mutator_on_a_transaction =
            noClasses()
                    .that()
                    .resideInAPackage(CHECK_IN)
                    .should()
                    .callMethodWhere(onTransactionOtherThan(TRANSACTION_READS))
                    .because(
                            "the rows a completion locks are read; the category is written by"
                                    + " TransactionService.recategorise and nothing else");

    @ArchTest
    static final ArchRule the_ledger_repository_only_selects =
            methods()
                    .that()
                    .areDeclaredIn(CheckInLedgerRepository.class)
                    .should()
                    .notBeAnnotatedWith(Modifying.class)
                    .andShould(beASelectQuery())
                    .because("the check-in reads the ledger and writes it only through its owner");

    private static DescribedPredicate<JavaCall<?>> onTransactionServiceOtherThan(
            Set<String> allowed) {
        return new DescribedPredicate<>("a TransactionService method other than " + allowed) {
            @Override
            public boolean test(JavaCall<?> call) {
                return call.getTargetOwner().isEquivalentTo(TransactionService.class)
                        && !allowed.contains(call.getName())
                        && !"<init>".equals(call.getName());
            }
        };
    }

    private static DescribedPredicate<JavaCall<?>> onTransactionOtherThan(Set<String> reads) {
        return new DescribedPredicate<>("a Transaction method that writes") {
            @Override
            public boolean test(JavaCall<?> call) {
                return call.getTargetOwner().isEquivalentTo(Transaction.class)
                        && !reads.contains(call.getName());
            }
        };
    }

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
                                method, select, method.getFullName() + " is not a JPQL select"));
            }
        };
    }
}
