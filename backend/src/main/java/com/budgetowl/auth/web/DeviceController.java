package com.budgetowl.auth.web;

import com.budgetowl.auth.domain.CredentialTransport;
import com.budgetowl.auth.service.Device;
import com.budgetowl.auth.service.DeviceService;
import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.auth.web.AuthResponses.DeviceResponse;
import com.budgetowl.common.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Where am I signed in?", and the button that ends one of them.
 *
 * <p>Sessions and mobile tokens in one list, each revocable, revocation immediate. Scoped to the
 * caller in the service on both reads and writes — a device id is a handle to somebody's live
 * credential, so "revoke device X" must never be answerable for an X that is not yours.
 */
@RestController
@RequestMapping("/api/auth/devices")
@Validated
public class DeviceController {

    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @GetMapping
    PageResponse<DeviceResponse> list(
            TransportAuthentication caller,
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<DeviceResponse> all =
                devices
                        .devicesOf(
                                caller.userId(),
                                currentSessionId(caller, request),
                                caller.tokenId().orElse(null))
                        .stream()
                        .map(DeviceController::response)
                        .toList();
        return PageResponse.of(all, page, size);
    }

    /**
     * @param id the handle from the list — {@code t-<uuid>} for a token, {@code s-<sha256>} for a
     *     session. Pattern-checked here so a malformed one is a {@code 400} naming the parameter
     *     rather than a lookup with rubbish in it.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(
            TransportAuthentication caller,
            @PathVariable @Pattern(regexp = "^[st]-[0-9a-fA-F-]{36,64}$") String id) {
        devices.revoke(caller.userId(), id);
    }

    private static String currentSessionId(
            TransportAuthentication caller, HttpServletRequest request) {
        if (caller.transport() != CredentialTransport.SESSION) {
            return null;
        }
        HttpSession session = request.getSession(false);
        return session == null ? null : session.getId();
    }

    private static DeviceResponse response(Device device) {
        return new DeviceResponse(
                device.id(),
                device.kind(),
                device.label(),
                device.createdAt(),
                device.lastUsedAt(),
                device.expiresAt(),
                device.current());
    }
}
