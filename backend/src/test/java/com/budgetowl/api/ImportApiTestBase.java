package com.budgetowl.api;

import com.budgetowl.imports.domain.ImportFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Helpers for driving file import over real HTTP: a file goes as base64 inside the JSON body,
 * exactly as the web client and the mobile client send it (ADR-0038).
 */
abstract class ImportApiTestBase extends ApiTestBase {

    /** The QIF date format the corpus uses. */
    static final String QIF_OPTIONS = "{\"dateFormat\":\"M/d'yy\"}";

    /** A CSV mapping for {@code csv-happy.csv}. */
    static final String CSV_OPTIONS =
            """
            {"delimiter":",","headerRow":1,"dateFormat":"dd/MM/uuuu","decimalSeparator":".",
             "groupingSeparator":",",
             "columns":{"0":"DATE","1":"PAYEE","2":"REFERENCE","3":"AMOUNT"}}
            """;

    static byte[] fixture(String name) {
        return ImportFixtures.bytes(name);
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String base64(byte[] file) {
        return Base64.getEncoder().encodeToString(file);
    }

    ApiResponse preview(
            ApiClient client, UUID accountId, String format, byte[] file, String options) {
        return client.post("/api/imports/preview", body(accountId, format, file, options, null));
    }

    ApiResponse commit(
            ApiClient client,
            UUID accountId,
            String format,
            byte[] file,
            String options,
            String digest,
            String extra) {
        String decisions =
                "\"previewDigest\":\"" + digest + "\"" + (extra == null ? "" : "," + extra);
        return client.post("/api/imports", body(accountId, format, file, options, decisions));
    }

    /** Preview, then commit with the preview's digest and the defaults. */
    ApiResponse previewThenCommit(
            ApiClient client, UUID accountId, String format, byte[] file, String options) {
        ApiResponse previewed = preview(client, accountId, format, file, options);
        if (previewed.status() != 200) {
            throw new AssertionError("preview failed: " + previewed.body());
        }
        return commit(
                client,
                accountId,
                format,
                file,
                options,
                previewed.stringField("previewDigest"),
                null);
    }

    static String body(
            UUID accountId, String format, byte[] file, String options, String decisions) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"accountId\":\"").append(accountId).append("\",");
        json.append("\"format\":\"").append(format).append("\",");
        json.append("\"file\":\"").append(base64(file)).append("\"");
        if (options != null) {
            json.append(",\"options\":").append(options);
        }
        if (decisions != null) {
            json.append(',').append(decisions);
        }
        return json.append('}').toString();
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    /**
     * Regular files under the temp directory written since {@code since} that hold {@code text}.
     */
    static List<Path> newFilesHolding(String text, Instant since) throws IOException {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(tmp, 6)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                try {
                    if (Files.isRegularFile(file)
                            && Files.size(file) < 64L * 1024 * 1024
                            && Files.getLastModifiedTime(file).toInstant().isAfter(since)
                            && new String(
                                            Files.readAllBytes(file),
                                            java.nio.charset.StandardCharsets.ISO_8859_1)
                                    .contains(text)) {
                        found.add(file);
                    }
                } catch (IOException | SecurityException unreadable) {
                    // Another process's file; not ours to read, and not one we wrote.
                }
            }
        } catch (java.io.UncheckedIOException walkedPastSomething) {
            // A directory vanished mid-walk. What was walked is still evidence.
        }
        return found;
    }
}
