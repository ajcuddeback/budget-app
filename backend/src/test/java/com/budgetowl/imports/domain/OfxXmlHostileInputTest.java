package com.budgetowl.imports.domain;

import static com.budgetowl.imports.domain.ImportFixtures.GBP;
import static com.budgetowl.imports.domain.ImportFixtures.none;
import static com.budgetowl.imports.domain.ImportFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * OFX 2.x cannot be made to read a file, fetch a URL or expand an entity
 * (docs/features/file-import.md, "Information disclosure and SSRF through XML").
 *
 * <p>The SSRF test does not stop at the {@code 400}: a parser that refused the file <em>after</em>
 * fetching the DTD would answer the same, and the attacker would already have their request. So a
 * local HTTP server records every hit, and the assertion is that it saw <b>none</b>.
 */
class OfxXmlHostileInputTest {

    private static final String SENTINEL = "XXE-SENTINEL-4f1c9a";

    @TempDir Path temporary;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void startTheTrap() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    hits.incrementAndGet();
                    byte[] body = SENTINEL.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        captured.start();
        root.addAppender(captured);
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void stopTheTrap() {
        server.stop(0);
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
    }

    @Test
    void aLocalFileEntityIsRefusedAndItsContentAppearsNowhere() throws IOException {
        Path secret = temporary.resolve("environ");
        Files.writeString(secret, SENTINEL);
        byte[] file = xxe(secret.toUri().toString());

        assertThatThrownBy(() -> reader().read(file, ImportFormat.OFX, GBP, none()))
                .isInstanceOfSatisfying(
                        ImportRefusal.class,
                        refusal -> {
                            assertThat(refusal.params()).containsEntry("reason", "dtd");
                            assertThat(refusal.getMessage()).doesNotContain(SENTINEL);
                        });
        assertThat(captured.list)
                .noneMatch(event -> event.getFormattedMessage().contains(SENTINEL));
    }

    @Test
    void anHttpEntityReachesNothingAtAll() {
        byte[] file =
                xxe("http://127.0.0.1:" + server.getAddress().getPort() + "/latest/meta-data");

        assertThatThrownBy(() -> reader().read(file, ImportFormat.OFX, GBP, none()))
                .isInstanceOfSatisfying(
                        ImportRefusal.class,
                        refusal -> assertThat(refusal.params()).containsEntry("reason", "dtd"));
        assertThat(hits.get()).as("requests the parser made on the attacker's behalf").isZero();
    }

    @Test
    void anExternalDtdReachesNothingAtAll() {
        String file =
                """
                <?xml version="1.0"?>
                <!DOCTYPE OFX SYSTEM "http://127.0.0.1:%d/ofx.dtd">
                <?OFX OFXHEADER="200" VERSION="211" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
                <OFX></OFX>
                """
                        .formatted(server.getAddress().getPort());

        assertThatThrownBy(
                        () ->
                                reader().read(
                                                file.getBytes(StandardCharsets.UTF_8),
                                                ImportFormat.OFX,
                                                GBP,
                                                none()))
                .isInstanceOf(ImportRefusal.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    void billionLaughsIsRefusedInUnderASecondWithoutGrowingTheHeap() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long started = System.nanoTime();

        assertThatThrownBy(
                        () ->
                                reader().read(
                                                ImportFixtures.bytes("ofx2-billion-laughs.ofx"),
                                                ImportFormat.OFX,
                                                GBP,
                                                none()))
                .isInstanceOfSatisfying(
                        ImportRefusal.class,
                        refusal -> assertThat(refusal.params()).containsEntry("reason", "dtd"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        long after = runtime.totalMemory() - runtime.freeMemory();
        // Ten levels of tenfold expansion is ten gigabytes. Sixty-four megabytes is generous noise.
        assertThat(after - before).isLessThan(64L * 1024 * 1024);
    }

    @Test
    void aQuadraticBlowupIsRefusedToo() {
        String big = "x".repeat(50_000);
        String file =
                """
                <?xml version="1.0"?>
                <!DOCTYPE OFX [<!ENTITY q "%s">]>
                <?OFX OFXHEADER="200" VERSION="211" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
                <OFX><NAME>%s</NAME></OFX>
                """
                        .formatted(big, "&q;".repeat(10_000));
        long started = System.nanoTime();

        assertThatThrownBy(
                        () ->
                                reader().read(
                                                file.getBytes(StandardCharsets.UTF_8),
                                                ImportFormat.OFX,
                                                GBP,
                                                none()))
                .isInstanceOfSatisfying(
                        ImportRefusal.class,
                        refusal -> assertThat(refusal.params()).containsEntry("reason", "dtd"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void anUndeclaredEntityIsNeverLookedUp() {
        String file =
                """
                <?xml version="1.0"?>
                <?OFX OFXHEADER="200" VERSION="211" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
                <OFX><NAME>&secret;</NAME></OFX>
                """;

        assertThatThrownBy(
                        () ->
                                reader().read(
                                                file.getBytes(StandardCharsets.UTF_8),
                                                ImportFormat.OFX,
                                                GBP,
                                                none()))
                .isInstanceOf(ImportRefusal.class);
    }

    @Test
    void aParserExceptionNeverCarriesItsMessageOut() {
        // Malformed XML whose error message would quote the text around it.
        String file =
                """
                <?xml version="1.0"?>
                <?OFX OFXHEADER="200" VERSION="211" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
                <OFX><NAME>%s</WRONG></OFX>
                """
                        .formatted(SENTINEL);

        assertThatThrownBy(
                        () ->
                                reader().read(
                                                file.getBytes(StandardCharsets.UTF_8),
                                                ImportFormat.OFX,
                                                GBP,
                                                none()))
                .isInstanceOfSatisfying(
                        ImportRefusal.class,
                        refusal -> {
                            assertThat(refusal.params()).containsEntry("reason", "not-a-statement");
                            assertThat(refusal.getMessage()).doesNotContain(SENTINEL);
                            assertThat(refusal.getCause()).isNull();
                        });
    }

    private static byte[] xxe(String system) {
        return ImportFixtures.text("ofx2-xxe.ofx.template")
                .replace("@@SYSTEM@@", system)
                .getBytes(StandardCharsets.UTF_8);
    }
}
