package com.signdesk.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Component
public class SecretStore {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretStore(
            DatabaseMigrator migrator,
            InstanceLock lock,
            @Value("${signdesk.secret-key:}") String configured,
            @Value("${signdesk.key-file}") String filename)
            throws Exception {
        Path path = Path.of(filename).toAbsolutePath().normalize();
        byte[] bytes;
        if (!configured.isBlank()) bytes = Base64.getDecoder().decode(configured.trim());
        else if (Files.exists(path))
            bytes = Base64.getDecoder().decode(Files.readString(path).trim());
        else {
            // Never generate a replacement key for an existing database with secrets.
            try (var connection =
                            java.sql.DriverManager.getConnection(
                                    "jdbc:sqlite:" + lock.directory().resolve("signdesk.db"));
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT EXISTS(SELECT 1 FROM request_revisions)"
                                    + " OR EXISTS(SELECT 1 FROM run_responses)")) {
                rows.next();
                if (rows.getInt(1) > 0)
                    throw new IllegalStateException(
                            "Master key is missing; restore the original key");
            }
            bytes = new byte[32];
            random.nextBytes(bytes);
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    Base64.getEncoder().encodeToString(bytes),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            try {
                Files.setPosixFilePermissions(
                        path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                /* Windows: protect the directory with OS permissions. */
            }
        }
        if (bytes.length != 32)
            throw new IllegalStateException("Master key must be 32 bytes in base64");
        key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String value, String aad) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce)
                    + "."
                    + Base64.getEncoder()
                            .encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt request");
        }
    }

    public String decrypt(String value, String aad) {
        try {
            String[] parts = value.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt request; check the master key");
        }
    }
}
