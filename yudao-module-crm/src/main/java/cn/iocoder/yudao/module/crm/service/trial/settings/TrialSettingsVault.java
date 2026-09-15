package cn.iocoder.yudao.module.crm.service.trial.settings;

import cn.iocoder.yudao.module.crm.service.trial.TrialException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Separate at-rest key, never returned by the admin API or stored alongside encrypted settings. */
@Component
public class TrialSettingsVault {
    private final Path file;
    public TrialSettingsVault(@Value("${mgs.trial-settings.key-file:/data/trial-settings/master.key}") String file) { this.file = Path.of(file); }
    public record Envelope(String nonce, String ciphertext) { }
    public Envelope encrypt(byte[] value, long tenant, long revision, boolean initialize) {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        try {
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce, tenant, revision, initialize);
            return new Envelope(Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(cipher.doFinal(value)));
        } catch (Exception e) { throw error(); }
    }
    public byte[] decrypt(String nonce, String ciphertext, long tenant, long revision) {
        try { return cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(nonce), tenant, revision, false).doFinal(Base64.getDecoder().decode(ciphertext)); }
        catch (Exception e) { throw error(); }
    }
    private Cipher cipher(int mode, byte[] nonce, long tenant, long revision, boolean initialize) throws Exception {
        byte[] key = key(initialize);
        try {
            if (nonce.length != 12) throw error();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("mgs-trial-settings-v1:" + tenant + ":" + revision).getBytes(StandardCharsets.UTF_8));
            return cipher;
        } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
    private synchronized byte[] key(boolean initialize) throws Exception {
        if (initialize && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(file.getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            byte[] value = new byte[32]; new SecureRandom().nextBytes(value);
            try (var channel = FileChannel.open(file, java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer = ByteBuffer.wrap(value);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            } catch (FileAlreadyExistsException ignored) { /* another instance initialized the shared key */ }
            finally { java.util.Arrays.fill(value, (byte) 0); }
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != 32) throw error();
        var permissions = Files.getPosixFilePermissions(file);
        if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) throw error();
        return Files.readAllBytes(file);
    }
    private RuntimeException error() { return TrialException.error(20, "配置加密存储不可用，请检查持久卷或恢复原有主密钥"); }
}
