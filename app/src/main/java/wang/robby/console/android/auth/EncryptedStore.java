package wang.robby.console.android.auth;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Callers serialize all accesses. Key material never leaves AndroidKeyStore. */
final class EncryptedStore {
    private static final String ALIAS = "wang.robby.console.android.oidc.v1";
    private static final byte[] AAD = (ALIAS + ":robby-console-android:ledger-v2").getBytes(StandardCharsets.UTF_8);
    private final AtomicFile file;
    EncryptedStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "oidc-session-v1.enc"));
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    JSONObject read() throws Exception {
        if (!file.getBaseFile().exists()) return new JSONObject();
        OAuthGuards.require(file.getBaseFile().length() <= 131072);
        byte[] bytes = file.readFully();
        OAuthGuards.require(bytes.length >= 29 && bytes[0] == 1);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 1, 13)));
        cipher.updateAAD(AAD);
        return new JSONObject(new String(cipher.doFinal(bytes, 13, bytes.length - 13), StandardCharsets.UTF_8));
    }
    void write(JSONObject object) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(object.toString().getBytes(StandardCharsets.UTF_8));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(1); stream.write(cipher.getIV()); stream.write(encrypted);
            file.finishWrite(stream);
        } catch (Exception exception) {
            if (stream != null) file.failWrite(stream);
            throw exception;
        }
    }
    void clear() {
        file.delete();
        // Also invalidate any residual encrypted file if filesystem deletion fails.
        try {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            keys.deleteEntry(ALIAS);
        } catch (Exception ignored) { /* Never expose keystore/provider details. */ }
    }
}
