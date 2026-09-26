package com.budgetowl.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param timeToLive how long an invitation link stays usable. The link is a credential that may sit
 *     in a chat backup, so its lifetime is a security setting rather than a convenience one.
 */
@ConfigurationProperties("budgetowl.invitations")
public record InvitationProperties(Duration timeToLive) {}
