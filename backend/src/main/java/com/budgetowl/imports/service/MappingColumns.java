package com.budgetowl.imports.service;

import com.budgetowl.imports.domain.CsvColumnRole;
import com.budgetowl.imports.domain.CsvMapping;
import com.budgetowl.imports.domain.ImportText;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code import_mappings.columns} object: roles, direction values, and what the header looked
 * like when the mapping was made.
 *
 * <p><b>Labels, never cells.</b> The labels of the mapped columns are kept so the mapping screen
 * can say "Column 2 · 'Description'"; the whole header is kept only as a signature, which is enough
 * to notice that the bank has changed its export format and too little to store anything else from
 * the file. Each label is cut to 32 characters, so the object always fits the database's 4 KiB
 * bound.
 */
final class MappingColumns {

    static final int LABEL_LENGTH = 32;

    private MappingColumns() {}

    /** What was saved, read back. */
    record Saved(
            Map<Integer, CsvColumnRole> roles,
            String directionIn,
            String directionOut,
            String headerSignature,
            Map<Integer, String> labels) {

        @Override
        public String toString() {
            return "Saved[roles=" + roles.size() + "]";
        }
    }

    static String encode(ObjectMapper json, CsvMapping mapping, List<String> headerLabels) {
        Map<String, Object> object = new LinkedHashMap<>();
        Map<String, String> roles = new TreeMap<>();
        Map<String, String> labels = new TreeMap<>();
        for (Map.Entry<Integer, CsvColumnRole> entry : mapping.columns().entrySet()) {
            roles.put(entry.getKey().toString(), entry.getValue().name());
            if (headerLabels != null && entry.getKey() < headerLabels.size()) {
                labels.put(
                        entry.getKey().toString(),
                        ImportText.cut(
                                ImportText.normalise(headerLabels.get(entry.getKey())),
                                LABEL_LENGTH));
            }
        }
        object.put("roles", roles);
        object.put("directionIn", mapping.directionIn());
        object.put("directionOut", mapping.directionOut());
        object.put("headerSignature", signature(headerLabels));
        object.put("labels", labels);
        return json.writeValueAsString(object);
    }

    @SuppressWarnings("unchecked")
    static Saved decode(ObjectMapper json, String text) {
        Map<String, Object> object = json.readValue(text, Map.class);
        Map<Integer, CsvColumnRole> roles = new TreeMap<>();
        Object rawRoles = object.get("roles");
        if (rawRoles instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                roles.put(
                        Integer.valueOf(entry.getKey().toString()),
                        CsvColumnRole.valueOf(entry.getValue().toString()));
            }
        }
        Map<Integer, String> labels = new TreeMap<>();
        Object rawLabels = object.get("labels");
        if (rawLabels instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                labels.put(
                        Integer.valueOf(entry.getKey().toString()),
                        String.valueOf(entry.getValue()));
            }
        }
        return new Saved(
                roles,
                (String) object.get("directionIn"),
                (String) object.get("directionOut"),
                (String) object.get("headerSignature"),
                labels);
    }

    /**
     * A SHA-256 over the normalised, case-folded labels, or {@code null} for a file with no header.
     * Two exports with the same columns in the same order have the same signature.
     */
    static String signature(List<String> headerLabels) {
        if (headerLabels == null || headerLabels.isEmpty()) {
            return null;
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every JDK has SHA-256", absent);
        }
        for (String label : headerLabels) {
            byte[] bytes =
                    ImportText.normalise(label)
                            .toLowerCase(Locale.ROOT)
                            .getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
