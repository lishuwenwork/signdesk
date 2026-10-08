package com.signdesk.storage;

import com.signdesk.common.Json;
import com.signdesk.engine.HutoolRequestExecutor;
import com.signdesk.engine.ResponseBody;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Base64;
import java.util.Locale;

@Component
public class RunResponseStore {
    public record Detail(
            String state, String body, String encoding, String contentType, String charset,
            int byteLength, int limitBytes) {}

    private final Db db;
    private final SecretStore secrets;

    public RunResponseStore(Db db, SecretStore secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    public String encrypt(String runId, ResponseBody response) {
        return secrets.encrypt(Json.write(response), "run-response:" + runId);
    }

    public void save(String runId, String ciphertext) {
        db.update("INSERT INTO run_responses(run_id,ciphertext) VALUES(?,?)"
                        + " ON CONFLICT(run_id) DO UPDATE SET ciphertext=excluded.ciphertext",
                runId, ciphertext);
    }

    public Detail detail(String runId) {
        var rows = db.rows("SELECT ciphertext FROM run_responses WHERE run_id=?", runId);
        if (rows.isEmpty()) return absent("not_recorded");
        try {
            var response = Json.read(secrets.decrypt(Db.text(rows.getFirst(), "ciphertext"),
                    "run-response:" + runId), ResponseBody.class);
            if (response == null) return absent("unavailable");
            byte[] bytes = response.bodyBytes();
            String state = response.truncated() ? "truncated" : response.complete() ? "complete" : "partial";
            String encoding = "text", body;
            try {
                if (binaryType(response.contentType())) throw new IllegalArgumentException();
                body = Charset.forName(response.charset()).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                if (body.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
                    throw new IllegalArgumentException();
            } catch (Exception e) {
                encoding = "base64";
                body = Base64.getEncoder().encodeToString(bytes);
            }
            return new Detail(state, body, encoding, response.contentType(), response.charset(),
                    bytes.length, HutoolRequestExecutor.MAX_RESPONSE_BYTES);
        } catch (Exception e) {
            return absent("decryption_failed");
        }
    }

    private static boolean binaryType(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/")
                || type.startsWith("font/") || type.equals("application/octet-stream")
                || type.equals("application/pdf") || type.equals("application/zip")
                || type.equals("application/gzip");
    }

    private static Detail absent(String state) {
        return new Detail(state, null, null, null, null, 0, HutoolRequestExecutor.MAX_RESPONSE_BYTES);
    }
}
