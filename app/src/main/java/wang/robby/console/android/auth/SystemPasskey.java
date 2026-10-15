package wang.robby.console.android.auth;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.credentials.CredentialManager;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.util.Log;
import java.util.concurrent.CompletableFuture;

/**
 * The platform Credential Manager (Android 14+) asked for a Passkey assertion, without AndroidX. The bundle keys are the
 * public Jetpack protocol constants that providers such as Google Password Manager read; Credential Manager also checks
 * auth.god.robby.wang's Digital Asset Links (delegate_permission/common.get_login_creds) before any provider answers.
 */
// No androidx.annotation in this framework-only app, so @RequiresApi is unavailable; every caller checks SDK_INT >= 34.
@SuppressLint("UseRequiresApi")
@TargetApi(34)
public final class SystemPasskey {
    private SystemPasskey() {}
    private static final String TYPE = "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL";
    static final String NO_CREDENTIAL = "这台手机上没有可用于 God 的通行密钥。可以在 Google 密码管理器同步通行密钥，或改用「通过已有设备授权」。";
    public static final String UNAVAILABLE = "系统暂时无法使用通行密钥，请确认 Google 密码管理器可用后重试。";

    public static boolean available(Context context) { return context.getSystemService(CredentialManager.class) != null; }

    /** {@code android:apk-key-hash:…} for this install's signing certificate, the origin the assertion will carry. */
    public static String origin(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        Signature[] signers = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        OAuthGuards.require(signers != null && signers.length == 1);
        return NativePasskey.origin(signers[0].toByteArray());
    }

    /** Shows the system sheet from {@code activity}; completes with the authentication response JSON or a {@link NativePasskeyFlow.Failure}. */
    public static void get(Activity activity, String requestJson, CancellationSignal signal, CompletableFuture<String> result) {
        Bundle data = option(requestJson), query = option(requestJson);
        Bundle metadata = new Bundle();
        metadata.putBoolean("androidx.credentials.BUNDLE_KEY_PREFER_IDENTITY_DOC_UI", false);
        metadata.putBoolean("androidx.credentials.BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS", false);
        GetCredentialRequest request = new GetCredentialRequest.Builder(metadata)
                .addCredentialOption(new CredentialOption.Builder(TYPE, data, query).setIsSystemProviderRequired(false).build()).build();
        CredentialManager manager = activity.getSystemService(CredentialManager.class);
        if (manager == null) { result.completeExceptionally(new NativePasskeyFlow.Failure(UNAVAILABLE, false)); return; }
        manager.getCredential(activity, request, signal, activity.getMainExecutor(), new OutcomeReceiver<GetCredentialResponse, GetCredentialException>() {
            @Override public void onResult(GetCredentialResponse response) {
                Bundle credential = response.getCredential().getData();
                String json = TYPE.equals(response.getCredential().getType()) && credential != null
                        ? credential.getString("androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON") : null;
                if (json != null) result.complete(json);
                else result.completeExceptionally(new NativePasskeyFlow.Failure(NativePasskeyFlow.INVALID, false));
            }
            @Override public void onError(GetCredentialException error) {
                String type = error.getType();
                // The type is a fixed platform/provider identifier; the message may describe the account, so it is not logged.
                Log.w("GodAuth", "credential manager: " + type);
                if (GetCredentialException.TYPE_USER_CANCELED.equals(type)) result.completeExceptionally(NativePasskeyFlow.cancelled());
                else if (GetCredentialException.TYPE_NO_CREDENTIAL.equals(type)) result.completeExceptionally(new NativePasskeyFlow.Failure(NO_CREDENTIAL, false));
                else result.completeExceptionally(new NativePasskeyFlow.Failure(UNAVAILABLE, false));
            }
        });
    }

    /** GetPublicKeyCredentialOption's request bundle: subtype, WebAuthn request JSON, auto-select and passkey priority. */
    private static Bundle option(String requestJson) {
        Bundle bundle = new Bundle();
        bundle.putString("androidx.credentials.BUNDLE_KEY_SUBTYPE", "androidx.credentials.BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION");
        bundle.putString("androidx.credentials.BUNDLE_KEY_REQUEST_JSON", requestJson);
        bundle.putBoolean("androidx.credentials.BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED", true);
        bundle.putInt("androidx.credentials.BUNDLE_KEY_TYPE_PRIORITY_VALUE", 100);
        return bundle;
    }
}
