package com.signdesk.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

final class BoundedJsonRequest extends HttpServletRequestWrapper {
    private final byte[] bytes;

    BoundedJsonRequest(HttpServletRequest request, byte[] bytes) {
        super(request);
        this.bytes = bytes;
    }

    @Override
    public ServletInputStream getInputStream() {
        var input = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return input.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("仅支持同步JSON读取");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return bytes.length;
    }

    @Override
    public long getContentLengthLong() {
        return bytes.length;
    }
}
