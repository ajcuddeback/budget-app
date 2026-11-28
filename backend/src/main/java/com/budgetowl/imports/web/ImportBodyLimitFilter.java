package com.budgetowl.imports.web;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.web.BodyTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts an import request's body <b>as it is read</b>, and refuses it the moment it passes the cap
 * (docs/features/file-import.md rule 3, "File size", layer b).
 *
 * <p>A declared {@code Content-Length} over the cap is refused here without reading a byte. A
 * chunked body, which declares nothing, is wrapped in a counting stream: whatever reads it —
 * Jackson — is stopped with {@link BodyTooLargeException} one byte past the cap, and {@code
 * ApiExceptionHandler} answers {@code 413}. Nothing is buffered here, so a two-gigabyte upload
 * costs at most the cap in memory and never reaches the controller or the parser.
 *
 * <p><b>Registered ahead of Spring Security, and it reads nothing itself.</b> The cheap refusals (a
 * declared length, a compressed body) need no identity, and a stranger is refused by them before
 * authentication is even attempted. The counting stream only matters once something reads the body,
 * and nothing reads the body of a request Spring Security refused — so an unauthenticated caller
 * can never make the server hold any of what they send. An earlier version buffered the body here,
 * which let an anonymous caller pin seven megabytes per connection; it was found while testing
 * nginx's chunked path, and is why nothing here reads.
 *
 * <p>{@code Content-Encoding} other than {@code identity} is refused with {@code 415}: a gzip body
 * inflates to a size nothing here counted.
 */
public final class ImportBodyLimitFilter extends OncePerRequestFilter {

    /** Writes a problem response; the filter chain's own writer, passed in to keep layers apart. */
    @FunctionalInterface
    public interface Refusal {
        void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
                throws IOException;
    }

    private final long maximumBodyBytes;
    private final Refusal refusal;

    public ImportBodyLimitFilter(long maximumBodyBytes, Refusal refusal) {
        this.maximumBodyBytes = maximumBodyBytes;
        this.refusal = refusal;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null
                && !encoding.isBlank()
                && !"identity".equalsIgnoreCase(encoding.strip())) {
            refusal.write(request, response, ErrorCode.IMPORT_CONTENT_ENCODING_REFUSED);
            return;
        }
        if (request.getContentLengthLong() > maximumBodyBytes) {
            refusal.write(request, response, ErrorCode.IMPORT_FILE_TOO_LARGE);
            return;
        }
        chain.doFilter(new CountedRequest(request, maximumBodyBytes), response);
    }

    /** The request, with a body that cannot be read past the cap. */
    private static final class CountedRequest extends HttpServletRequestWrapper {

        private final long maximum;
        private ServletInputStream counted;

        CountedRequest(HttpServletRequest request, long maximum) {
            super(request);
            this.maximum = maximum;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (counted == null) {
                counted = new Counting(super.getInputStream(), maximum);
            }
            return counted;
        }

        /**
         * Always UTF-8, which is what JSON is. Never {@code Charset.forName} on the request's own
         * {@code Content-Type}, which is a name the caller chose.
         */
        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class Counting extends ServletInputStream {

        private final ServletInputStream inner;
        private final long maximum;
        private long read;

        Counting(ServletInputStream inner, long maximum) {
            this.inner = inner;
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int next = inner.read();
            if (next >= 0) {
                count(1);
            }
            return next;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            int got = inner.read(target, offset, length);
            if (got > 0) {
                count(got);
            }
            return got;
        }

        private void count(int more) throws BodyTooLargeException {
            read += more;
            if (read > maximum) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return inner.isFinished();
        }

        @Override
        public boolean isReady() {
            return inner.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            inner.setReadListener(listener);
        }
    }
}
