package com.budgetowl.imports.web;

import com.budgetowl.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts an import request's body <b>before anything reads it</b> (docs/features/file-import.md
 * rule 3, "File size", layer b).
 *
 * <p>A declared {@code Content-Length} over the cap is refused without reading a byte. A chunked
 * body, which declares nothing, is read up to the cap and refused the moment it passes it — so a
 * two-gigabyte upload, or one that never says how long it is, costs at most the cap in memory and
 * never reaches Jackson, the controller or the parser. Under the cap, the bytes already read are
 * handed on, so nothing reads the network twice.
 *
 * <p>{@code Content-Encoding} other than {@code identity} is refused with {@code 415}: a gzip body
 * inflates to a size nothing here counted.
 *
 * <p>The cap is sized for base64 (a third larger than the file) plus the JSON around it. The
 * decoded file is checked against the 5 MiB cap again after decoding (layer c); nginx's {@code
 * client_max_body_size} on {@code location /api/imports} is layer a.
 */
public final class ImportBodyLimitFilter extends OncePerRequestFilter {

    /** Writes a problem response; the filter chain's own writer, passed in to keep layers apart. */
    @FunctionalInterface
    public interface Refusal {
        void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
                throws IOException;
    }

    private static final int CHUNK = 64 * 1024;

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
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = readUpTo(request.getInputStream(), maximumBodyBytes);
        if (body == null) {
            refusal.write(request, response, ErrorCode.IMPORT_FILE_TOO_LARGE);
            return;
        }
        chain.doFilter(new CountedRequest(request, body), response);
    }

    /** The body, or {@code null} the moment it passes the cap. */
    private static byte[] readUpTo(InputStream in, long maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(CHUNK);
        byte[] buffer = new byte[CHUNK];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maximum) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** The request with its body already in hand and counted. */
    private static final class CountedRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CountedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return in.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous only");
                }
            };
        }

        /**
         * Always UTF-8, which is what JSON is. Never {@code Charset.forName} on the request's own
         * {@code Content-Type}, which is a name the caller chose.
         */
        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
