package com.budgetowl.auth.domain;

/**
 * How the caller proved who they are: a session cookie from a browser, or an opaque bearer token
 * from the mobile app (ADR-0018).
 *
 * <p><b>Two transports, one authorization.</b> Nothing downstream of authentication may branch on
 * this — an endpoint that authorizes correctly for the web and not for mobile is a real and easy
 * bug, and every endpoint has a test proving it does not (docs/guides/testing-style.md). The single
 * legitimate difference is CSRF, which applies to the cookie transport only because a bearer
 * request carries no ambient credential to forge.
 */
public enum CredentialTransport {
    SESSION,
    BEARER
}
