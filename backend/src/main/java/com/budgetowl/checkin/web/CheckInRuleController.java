package com.budgetowl.checkin.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.checkin.service.ChargeRuleService;
import com.budgetowl.checkin.service.CheckInViews.ChargeRuleView;
import com.budgetowl.checkin.service.CheckInViews.Page;
import com.budgetowl.checkin.service.CheckInViews.PayeeRuleView;
import com.budgetowl.checkin.service.PayeeRuleService;
import com.budgetowl.common.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payee and charge rules: read and removed, <b>never created or edited here</b>. Rules are written
 * only by completing a check-in (ADR-0041), so there is no {@code POST} and no {@code PATCH}.
 */
@RestController
public class CheckInRuleController {

    private final PayeeRuleService payeeRules;
    private final ChargeRuleService chargeRules;

    public CheckInRuleController(PayeeRuleService payeeRules, ChargeRuleService chargeRules) {
        this.payeeRules = payeeRules;
        this.chargeRules = chargeRules;
    }

    @GetMapping("/api/payee-rules")
    PageResponse<PayeeRuleView> payeeRules(
            TransportAuthentication caller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return page(payeeRules.list(caller.userId(), page, size));
    }

    @DeleteMapping("/api/payee-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removePayeeRule(TransportAuthentication caller, @PathVariable UUID id) {
        payeeRules.remove(caller.userId(), id);
    }

    @GetMapping("/api/charge-rules")
    PageResponse<ChargeRuleView> chargeRules(
            TransportAuthentication caller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return page(chargeRules.list(caller.userId(), page, size));
    }

    @DeleteMapping("/api/charge-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeChargeRule(TransportAuthentication caller, @PathVariable UUID id) {
        chargeRules.remove(caller.userId(), id);
    }

    private static <T> PageResponse<T> page(Page<T> found) {
        return new PageResponse<>(
                found.content(),
                found.page(),
                found.size(),
                found.totalElements(),
                found.totalPages());
    }
}
