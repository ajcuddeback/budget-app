package com.budgetowl.instance.web;

import com.budgetowl.common.web.ClientAddress;
import com.budgetowl.instance.service.InstanceMetaService;
import com.budgetowl.instance.service.InstanceMetaService.InstanceMeta;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/meta}: identifies a Budget Owl server and its API version (ADR-0042).
 *
 * <p><b>The sixth public route</b>, after setup status, first user, login, token issue and
 * invitation acceptance — listed by name in {@code SecurityConfig} and in {@code AuthorizationIT}'s
 * public set. It is public because the mobile app asks it <em>before</em> sending a password: the
 * password goes only to an address that has answered this over verified TLS.
 *
 * <p>{@code Cache-Control: no-store}, so an intermediary never answers for a server that has since
 * moved or been upgraded.
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final InstanceMetaService meta;

    public MetaController(InstanceMetaService meta) {
        this.meta = meta;
    }

    @GetMapping
    ResponseEntity<MetaResponse> describe(HttpServletRequest request) {
        InstanceMeta described = meta.describe(ClientAddress.of(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new MetaResponse(
                                described.app(),
                                described.apiVersion(),
                                described.minimumClientApiVersion()));
    }

    /**
     * The body, in exactly this order. Nothing that fingerprints an instance beyond "this is Budget
     * Owl, API N" — see {@link InstanceMetaService}.
     */
    public record MetaResponse(String app, int apiVersion, int minimumClientApiVersion) {}
}
