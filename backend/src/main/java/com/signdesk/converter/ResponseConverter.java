package com.signdesk.converter;

import com.signdesk.domain.RunResponse;
import com.signdesk.domain.vo.ResponseDetailVo;
import com.signdesk.engine.HutoolRequestExecutor;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

public final class ResponseConverter {
    private ResponseConverter() {}

    public static ResponseDetailVo detail(RunResponse response) {
        if (response == null) return absent("not_recorded");
        if (response.getCaptureState().equals("unavailable") || response.getBodyBytes() == null)
            return absent("unavailable");
        byte[] bytes = response.getBodyBytes();
        String encoding = "text", body;
        try {
            if (binaryType(response.getContentType())) throw new IllegalArgumentException();
            body =
                    Charset.forName(response.getCharset())
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            if (body.chars()
                    .anyMatch(
                            c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
                throw new IllegalArgumentException();
        } catch (Exception e) {
            encoding = "base64";
            body = Base64.getEncoder().encodeToString(bytes);
        }
        return new ResponseDetailVo(
                response.getCaptureState(),
                body,
                encoding,
                response.getContentType(),
                response.getCharset(),
                bytes.length,
                HutoolRequestExecutor.MAX_RESPONSE_BYTES);
    }

    private static boolean binaryType(String contentType) {
        String type =
                contentType == null
                        ? ""
                        : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return type.startsWith("image/")
                || type.startsWith("audio/")
                || type.startsWith("video/")
                || type.startsWith("font/")
                || Set.of(
                                "application/octet-stream",
                                "application/pdf",
                                "application/zip",
                                "application/gzip")
                        .contains(type);
    }

    private static ResponseDetailVo absent(String state) {
        return new ResponseDetailVo(
                state, null, null, null, null, 0, HutoolRequestExecutor.MAX_RESPONSE_BYTES);
    }
}
