package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.budgetowl.imports.domain.ImportLimits;
import com.budgetowl.imports.service.ImportGate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The limits that keep one upload from taking the instance down (docs/features/file-import.md rule
 * 3 and "Denial of service through size and shape").
 *
 * <p>The size tests use a raw HTTP client, because the point is what happens to bytes nothing has
 * parsed yet: one request declares its length, the other streams chunked with no {@code
 * Content-Length} at all, and both must be refused with {@code 413} and <b>no parse</b> — asserted
 * by the absence of the one log line every parse writes.
 */
class ImportLimitsIT extends ImportApiTestBase {

    @Autowired private ImportGate gate;
    @Autowired private ImportLimits limits;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
    }

    @Test
    void aBodyOverTheCapIsRefusedWithAndWithoutContentLengthAndNeverParsed() throws Exception {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        byte[] body = oversizedBody(account);
        captured.start();
        root.addAppender(captured);

        HttpResponse<String> declared =
                raw(household.owner(), HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> chunked =
                raw(
                        household.owner(),
                        HttpRequest.BodyPublishers.ofInputStream(
                                () -> new ByteArrayInputStream(body)));

        assertThat(declared.statusCode()).isEqualTo(413);
        assertThat(declared.body()).contains("\"code\":\"import-file-too-large\"");
        assertThat(chunked.statusCode()).isEqualTo(413);
        assertThat(chunked.body()).contains("\"code\":\"import-file-too-large\"");
        assertThat(captured.list)
                .as("refused before anything parsed it")
                .noneMatch(event -> event.getFormattedMessage().startsWith("import preview"));
    }

    @Test
    void aStrangerSendingAnOversizedBodyGets413BeforeAuthentication() throws Exception {
        seedHousehold();
        byte[] body = oversizedBody(UUID.randomUUID());

        HttpResponse<String> refused =
                raw(anonymous(), HttpRequest.BodyPublishers.ofByteArray(body));

        assertThat(refused.statusCode()).isEqualTo(413);
    }

    @Test
    void aStrangerStreamingAnUndeclaredBodyIsRefusedByAuthenticationWithoutItBeingRead()
            throws Exception {
        seedHousehold();
        byte[] body = oversizedBody(UUID.randomUUID());

        HttpResponse<String> refused =
                raw(
                        browser(),
                        HttpRequest.BodyPublishers.ofInputStream(
                                () -> new java.io.ByteArrayInputStream(body)));

        // Nothing reads the body of a request Spring Security refused, so the counting stream is
        // never consulted and nothing of the upload is held.
        assertThat(refused.statusCode()).isEqualTo(401);
    }

    @Test
    void aFileOverFiveMebibytesInsideAnAcceptableBodyIsRefusedAfterDecoding() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        byte[] file = new byte[ImportLimits.FIVE_MIB + 1];
        java.util.Arrays.fill(file, (byte) 'a');

        ApiResponse refused = preview(household.owner(), account, "CSV", file, null);

        assertThat(refused.status()).isEqualTo(413);
        assertThat(refused.stringField("code")).isEqualTo("import-file-too-large");
    }

    @Test
    void aCompressedRequestBodyIsRefusedWith415() throws Exception {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        String body = body(account, "CSV", utf8("Date,Amount\n"), null, null);

        HttpResponse<String> refused =
                raw(
                        household.owner(),
                        HttpRequest.BodyPublishers.ofString(body),
                        Map.of("Content-Encoding", "gzip"));

        assertThat(refused.statusCode()).isEqualTo(415);
        assertThat(refused.body()).contains("\"code\":\"import-content-encoding-refused\"");
    }

    @Test
    void aMultipartUploadIsRefusedAndLeavesNothingOnDisk() throws Exception {
        Household household = seedHousehold();
        java.time.Instant started = java.time.Instant.now();

        HttpResponse<String> refused =
                raw(
                        household.owner(),
                        HttpRequest.BodyPublishers.ofString(
                                "--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.ofx\"\r\n\r\nOFXHEADER:100\r\n--x--\r\n"),
                        Map.of("Content-Type", "multipart/form-data; boundary=x"));

        assertThat(refused.statusCode()).isEqualTo(415);
        assertThat(newFilesHolding("OFXHEADER:100", started))
                .as("multipart is off, so nothing spooled a part to java.io.tmpdir (ADR-0038)")
                .isEmpty();
    }

    @Test
    void aFileAtTheCapImportsAllTenThousandRowsWithinTheLimits() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        StringBuilder csv = new StringBuilder();
        String payee = "P".repeat(500);
        for (int row = 0; row < 10_000; row++) {
            csv.append("2026-08-01,").append(payee).append(",-1.00\n");
        }
        byte[] file = csv.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(file.length).isLessThanOrEqualTo(ImportLimits.FIVE_MIB);
        String options =
                "{\"delimiter\":\",\",\"headerRow\":0,\"dateFormat\":\"uuuu-MM-dd\",\"decimalSeparator\":\".\",\"columns\":{\"0\":\"DATE\",\"1\":\"PAYEE\",\"2\":\"AMOUNT\"}}";

        ApiResponse previewed = preview(household.owner(), account, "CSV", file, options);
        ApiResponse committed =
                commit(
                        household.owner(),
                        account,
                        "CSV",
                        file,
                        options,
                        previewed.stringField("previewDigest"),
                        null);

        assertThat(previewed.status()).as("Jackson's string limit is above the cap").isEqualTo(200);
        assertThat(previewed.numberField("new")).isEqualTo(10_000);
        assertThat(committed.status()).isEqualTo(201);
        assertThat(count("transactions")).isEqualTo(10_000);
        assertThat(count("payees")).as("ten thousand identical payees are one").isEqualTo(1);
    }

    @Test
    void theThirtyFirstParseInTenMinutesIsRateLimited() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        byte[] file = utf8("Date,Amount\n2026-08-01,-1.00\n");
        for (int call = 0; call < 30; call++) {
            assertThat(preview(household.owner(), account, "CSV", file, null).status())
                    .isEqualTo(200);
        }

        ApiResponse refused = preview(household.owner(), account, "CSV", file, null);

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.stringField("code")).isEqualTo("rate-limited");
        assertThat(refused.header("Retry-After")).isPresent();
    }

    @Test
    void aSecondParseForTheSameUserWhileOneIsRunningIsRefused() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        try (ImportGate.Permit running = gate.enter(userIdOf(OWNER_EMAIL))) {
            ApiResponse refused =
                    preview(household.owner(), account, "CSV", utf8("Date,Amount\n"), null);

            assertThat(refused.status()).isEqualTo(429);
            assertThat(refused.header("Retry-After")).isPresent();
            assertThat(
                            preview(household.member(), account, "CSV", utf8("Date,Amount\n"), null)
                                    .status())
                    .as("another member is not held up by Ada's parse")
                    .isEqualTo(200);
        }
        assertThat(preview(household.owner(), account, "CSV", utf8("Date,Amount\n"), null).status())
                .isEqualTo(200);
    }

    @Test
    void aThirdParseOnTheInstanceWhileTwoAreRunningIsRefused() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        try (ImportGate.Permit one = gate.enter(UUID.randomUUID());
                ImportGate.Permit two = gate.enter(UUID.randomUUID())) {
            ApiResponse refused =
                    preview(household.owner(), account, "CSV", utf8("Date,Amount\n"), null);

            assertThat(refused.status()).isEqualTo(429);
        }
    }

    // ------------------------------------------------------------------------------- internals

    /** A syntactically ordinary preview whose body is one byte over the cap. */
    private byte[] oversizedBody(UUID account) {
        long cap = limits.maximumBodyBytes();
        String prefix = "{\"accountId\":\"" + account + "\",\"format\":\"CSV\",\"file\":\"";
        String suffix = "\"}";
        int padding = Math.toIntExact(cap + 1 - prefix.length() - suffix.length());
        return (prefix + "A".repeat(padding) + suffix).getBytes(StandardCharsets.US_ASCII);
    }

    private HttpResponse<String> raw(ApiClient client, HttpRequest.BodyPublisher body)
            throws IOException, InterruptedException {
        return raw(client, body, Map.of());
    }

    private HttpResponse<String> raw(
            ApiClient client, HttpRequest.BodyPublisher body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/imports/preview"))
                        .timeout(Duration.ofSeconds(60))
                        .POST(body)
                        .header(
                                "Content-Type",
                                headers.getOrDefault("Content-Type", "application/json"));
        headers.forEach(
                (name, value) -> {
                    if (!name.equals("Content-Type")) {
                        request.header(name, value);
                    }
                });
        String cookies =
                client.cookies().entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("");
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookies);
        }
        client.cookie("XSRF-TOKEN").ifPresent(token -> request.header("X-XSRF-TOKEN", token));
        return HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
