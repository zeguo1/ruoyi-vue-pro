package cn.iocoder.yudao.module.crm.service.trial;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Per-application AES-GCM envelope. Authentication keys and encryption keys must be separate. */
@Component
@RequiredArgsConstructor
public class TrialLoginVault {
    private final TrialProperties properties;
    private static final SecureRandom RANDOM = new SecureRandom();
    public record Envelope(String keyId, String nonce, String ciphertext) { }

    public static String newPassword() {
        byte[] bytes = new byte[12]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 96 bits; existing login supports at most 16 chars.
    }
    public Envelope encrypt(String binding, String password) {
        if (properties.getLoginDelivery() == null || !properties.getLoginDelivery().ready()) { throw TrialException.unavailable(); }
        String keyId = properties.getLoginDelivery().getActiveKeyId();
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        try {
            var cipher = cipher(Cipher.ENCRYPT_MODE, keyId, nonce, binding);
            return new Envelope(keyId, Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.GeneralSecurityException ex) { throw TrialException.unavailable(); }
    }
    public String decrypt(String binding, Envelope envelope) {
        if (envelope == null || envelope.nonce() == null || envelope.ciphertext() == null || envelope.keyId() == null) { throw TrialException.unavailable(); }
        try {
            byte[] nonce = Base64.getDecoder().decode(envelope.nonce());
            byte[] encrypted = Base64.getDecoder().decode(envelope.ciphertext());
            if (nonce.length != 12 || encrypted.length < 16) { throw TrialException.unavailable(); }
            var cipher = cipher(Cipher.DECRYPT_MODE, envelope.keyId(), nonce, binding);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | java.security.ProviderException | IllegalArgumentException ex) { throw TrialException.unavailable(); }
    }
    private Cipher cipher(int mode, String keyId, byte[] nonce, String binding) throws java.security.GeneralSecurityException {
        byte[] key;
        try { key = Base64.getDecoder().decode(properties.getLoginDelivery().getEncryptionKeys().get(keyId)); }
        catch (RuntimeException ex) { throw TrialException.unavailable(); }
        if (key.length != 32) { throw TrialException.unavailable(); }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            return cipher;
        } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
}
