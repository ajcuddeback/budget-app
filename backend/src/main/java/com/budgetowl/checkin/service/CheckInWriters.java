package com.budgetowl.checkin.service;

import com.budgetowl.bill.service.BillService;
import com.budgetowl.debt.service.DebtPaymentService;
import com.budgetowl.goal.service.GoalService;
import com.budgetowl.transaction.service.TransactionService;
import org.springframework.stereotype.Component;

/**
 * The owning services a completed check-in writes through, and <b>no others</b>. Gathered in one
 * place so that what the check-in can change is a list a reviewer can read:
 *
 * <ul>
 *   <li>a transaction's category, and nothing else about it ({@link
 *       TransactionService#recategorise});
 *   <li>a bill occurrence's status ({@link BillService#recordFromCheckIn});
 *   <li>a goal contribution and a debt payment, each a plan record (ADR-0035);
 *   <li>payee and charge rules.
 * </ul>
 *
 * <p>{@code CheckInArchitectureTest} holds the first: the check-in package calls no other method on
 * {@code TransactionService} and reaches no transaction or transfer repository at all.
 */
@Component
public class CheckInWriters {

    final TransactionService transactions;
    final BillService bills;
    final GoalService goals;
    final DebtPaymentService debtPayments;
    final PayeeRuleService payeeRules;
    final ChargeRuleService chargeRules;

    public CheckInWriters(
            TransactionService transactions,
            BillService bills,
            GoalService goals,
            DebtPaymentService debtPayments,
            PayeeRuleService payeeRules,
            ChargeRuleService chargeRules) {
        this.transactions = transactions;
        this.bills = bills;
        this.goals = goals;
        this.debtPayments = debtPayments;
        this.payeeRules = payeeRules;
        this.chargeRules = chargeRules;
    }
}
