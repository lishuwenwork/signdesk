package com.signdesk.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.common.ApiException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

class CurlParserTest {
    private final CurlParser parser = new CurlParser();

    @Test
    void preservesWholeRequest() {
        String url = "https://example.com/check?a=%2f%2F&x=1&x=2&sig=a+b%20c";
        var s =
                parser.parse(
                                "curl '"
                                        + url
                                        + "' -H 'Authorization: Bearer sample' -H 'Cookie:"
                                        + " account=A' -H 'X-Device: phone' -H 'X-Test: first' -H"
                                        + " 'X-Test: second' --data-raw '{\"code\": 0,"
                                        + " \"nested\":{\"v\":\"原样\"}}'")
                        .spec();
        assertEquals(url, s.rawUrl());
        assertEquals("POST", s.method());
        assertEquals(2, s.headers().stream().filter(h -> h.name().equals("X-Test")).count());
        assertEquals(
                "{\"code\": 0, \"nested\":{\"v\":\"原样\"}}",
                new String(s.bodyBytes(), StandardCharsets.UTF_8));
        byte[] returned = s.bodyBytes();
        returned[0] = 0;
        assertEquals('{', s.bodyBytes()[0]);
        assertThrows(UnsupportedOperationException.class, () -> s.headers().clear());
    }

    @Test
    void handlesCmdCopy() {
        var s =
                parser.parse(
                                "curl ^\"https://example.com/check?a=1^\" ^\n"
                                        + "  -H ^\"Content-Type: application/json^\" ^\n"
                                        + "  --data-raw ^\"{^\\^\"value^\\^\":^\\^\"text^\\^\"}^\"")
                        .spec();
        assertEquals("{\"value\":\"text\"}", new String(s.bodyBytes(), StandardCharsets.UTF_8));
        assertEquals("POST", s.method());
    }

    @Test
    void handlesDataSemantics() {
        assertEquals(
                "a=1&b=2",
                new String(
                        parser.parse("curl https://example.com -d 'a=1' -d 'b=2'")
                                .spec()
                                .bodyBytes(),
                        StandardCharsets.UTF_8));
        assertEquals(
                "https://example.com?before=1&a=hello%20world&a=%E4%BD%A0",
                parser.parse(
                                "curl 'https://example.com?before=1' -G --data-urlencode 'a=hello"
                                        + " world' --data-urlencode 'a=你'")
                        .spec()
                        .rawUrl());
        assertEquals(
                "hello\nworld",
                new String(
                        parser.parse("curl https://example.com --data-binary $'hello\\nworld'")
                                .spec()
                                .bodyBytes(),
                        StandardCharsets.UTF_8));
        assertEquals("HEAD", parser.parse("curl -I https://example.com").spec().method());
    }

    @Test
    void explicitTimeoutUsesGlobalAsSeparateCap() {
        assertEquals(0, parser.parse("curl https://example.com").spec().timeoutMillis());
        assertEquals(
                60000,
                parser.parse("curl https://example.com --max-time 60").spec().timeoutMillis());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "curl 'file:///etc/passwd'", "curl https://example.com -d @secret.txt",
                        "curl https://example.com --config settings",
                "curl https://example.com; whoami", "curl https://example.com | sh",
                        "curl \"https://example.com?value=$(id)\"",
                "curl https://example.com -F 'file=@file.txt'", "curl https://example.com -k",
                        "curl https://example.com --http2",
                "curl https://example.com -X GET -d 'a=1'",
                        "curl https://example.com -H 'Host: other.example'",
                        "curl https://example.com -H 'Accept-Encoding: br'",
                "curl 'https://user:password@example.com/'",
                        "curl https://example.com -H 'X-Name: first\nInjected: second'"
            })
    void rejectsUnsupportedOrUnsafe(String input) {
        assertThrows(ApiException.class, () -> parser.parse(input));
    }
}
