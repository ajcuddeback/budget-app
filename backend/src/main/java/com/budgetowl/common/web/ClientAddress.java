package com.budgetowl.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's address, for rate limiting and for the authentication audit trail.
 *
 * <p><b>The socket's address, never a forwarded header.</b> {@code X-Forwarded-For} is set freely
 * by whoever is making the request, so a rate limiter keyed on it is throttling a value the
 * attacker picks — a different one per attempt. A deployment behind a reverse proxy tells the
 * server to trust the proxy's headers explicitly ({@code server.forward-headers-strategy}), at
 * which point the container rewrites the remote address and this method keeps telling the truth.
 */
public final class ClientAddress {

    private static final String UNKNOWN = "unknown";

    private ClientAddress() {}

    public static String of(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? UNKNOWN : address;
    }
}
