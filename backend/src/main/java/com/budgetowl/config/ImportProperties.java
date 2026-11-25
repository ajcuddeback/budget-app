package com.budgetowl.config;

import com.budgetowl.imports.domain.ImportLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code budgetowl.import.*}: the limits file import enforces (docs/features/file-import.md rule 3,
 * decision 1).
 *
 * <p><b>Fixed configuration, not per-request values.</b> They are here, rather than as constants,
 * so the one in {@code application.yml} is the one a reviewer reads — and the defaults below are
 * the decided values, so a deployment that sets nothing gets exactly those. A limit can be raised
 * later more easily than lowered; raising {@code maximum-file-size} also means raising nginx's
 * {@code client_max_body_size} on {@code location /api/imports} (frontend/nginx.conf), or the proxy
 * refuses what the API would accept.
 */
@ConfigurationProperties("budgetowl.import")
public record ImportProperties(
        @DefaultValue("5MB") DataSize maximumFileSize,
        @DefaultValue("12000") int csvMaximumLines,
        @DefaultValue("200000") int qifMaximumLines,
        @DefaultValue("4096") int maximumLineLength,
        @DefaultValue("10000") int maximumRows,
        @DefaultValue("64") int csvMaximumColumns,
        @DefaultValue("32") int ofxMaximumDepth,
        @DefaultValue("200000") int ofxMaximumElements,
        @DefaultValue("64") int ofxMaximumNameLength,
        @DefaultValue("1024") int ofxMaximumLeafLength,
        @DefaultValue("15s") Duration parseDeadline) {

    public ImportLimits limits() {
        return new ImportLimits(
                Math.toIntExact(maximumFileSize.toBytes()),
                csvMaximumLines,
                qifMaximumLines,
                maximumLineLength,
                maximumRows,
                csvMaximumColumns,
                ofxMaximumDepth,
                ofxMaximumElements,
                ofxMaximumNameLength,
                ofxMaximumLeafLength,
                parseDeadline);
    }
}
