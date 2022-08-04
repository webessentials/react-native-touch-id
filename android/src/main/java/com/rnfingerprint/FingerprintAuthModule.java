package com.rnfingerprint;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.module.annotations.ReactModule;

import java.util.concurrent.Executor;

@ReactModule(name = FingerprintAuthModule.NAME)
public class FingerprintAuthModule extends ReactContextBaseJavaModule implements LifecycleEventListener, RetryCallback {
    public static final String NAME = "FingerprintAuth";
    public static final int CONFIRM_DEVICE_CREDENTIAL_CODE = 10001;
    private static final String FRAGMENT_TAG = "fingerprint_dialog";

    private KeyguardManager keyguardManager;

    private boolean isAppActive = false;
    private boolean authSuccess = false;
    private boolean inProgress = false;
    private Callback reactSuccessCallback;

    private BiometricBackground background;
    private BiometricPrompt prompt;
    private BiometricPrompt.PromptInfo promptInfo;
    private String reason = "Authenticate";
    private int confirmDeviceCredentialCode = CONFIRM_DEVICE_CREDENTIAL_CODE;

    public FingerprintAuthModule(final ReactApplicationContext reactContext) {
        super(reactContext);

        reactContext.addLifecycleEventListener(this);
    }

    private KeyguardManager getKeyguardManager() {
        if (keyguardManager != null) {
            return keyguardManager;
        }
        final Activity activity = getCurrentActivity();
        if (activity == null) {
            return null;
        }

        keyguardManager = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);

        return keyguardManager;
    }

    @Override
    public String getName() {
        return "FingerprintAuth";
    }

    @ReactMethod
    public void isSupported(final Callback reactErrorCallback, final Callback reactSuccessCallback) {
        final Activity activity = getCurrentActivity();
        if (activity == null) {
            return;
        }

        int result = isFingerprintAuthAvailable();
        if (result == FingerprintAuthConstants.IS_SUPPORTED) {
            reactSuccessCallback.invoke("Biometric");
        } else {
            reactErrorCallback.invoke("Not supported.", result);
        }
    }

    void showDeviceCredentialActivity(Activity activity, String title, String description, int resultCode)
    {
        Intent intent = keyguardManager.createConfirmDeviceCredentialIntent(title, description);
        if (intent != null) {
            activity.startActivityForResult(intent, resultCode);
        }
    }

    @TargetApi((Build.VERSION_CODES.M))
    @ReactMethod
    public void deviceCredentialActivityResult(int resultCode) {
        authSuccess = (resultCode == Activity.RESULT_OK);
        inProgress = false;
        authFinished();
    }

    @TargetApi(Build.VERSION_CODES.M)
    @ReactMethod
    public void authenticateOnActivity(final FragmentActivity activity, final String reason, final ReadableMap authConfig, final Callback reactErrorCallback, final Callback reactSuccessCallback) {
        authenticateOnActivity(activity, reason, authConfig, reactErrorCallback, reactSuccessCallback, CONFIRM_DEVICE_CREDENTIAL_CODE);
    }

    @TargetApi(Build.VERSION_CODES.M)
    @ReactMethod
    public void authenticateOnActivity(final FragmentActivity activity, final String reason, final ReadableMap authConfig, final Callback reactErrorCallback, final Callback reactSuccessCallback, int confirmDeviceCredentialCode) {
        if (inProgress || activity == null) {
            return;
        }
        // authentication on different activity requires manual activation with call to hostResume
        if (activity != getCurrentActivity()) {
            isAppActive = false;
        }
        inProgress = true;
        authSuccess = false;

        this.reactSuccessCallback = reactSuccessCallback;
        this.confirmDeviceCredentialCode = confirmDeviceCredentialCode;
        this.reason = reason;

        String cancelText = "Cancel";
        if (authConfig.hasKey("cancelText")) {
            cancelText = authConfig.getString("cancelText");
        }

        String retryText = "Retry";
        if (authConfig.hasKey("retryText")) {
            retryText = authConfig.getString("retryText");
        }

        int availableResult = isFingerprintAuthAvailable();
        boolean isAvailable = availableResult != FingerprintAuthConstants.IS_SUPPORTED;
        if (isAvailable) {
            inProgress = false;
            reactErrorCallback.invoke("Not supported", availableResult);
            return;
        }

        if (authConfig.hasKey("useBackground") && authConfig.getBoolean("useBackground")) {
            // Use singleton for avoiding duplication
            background = BiometricBackground.getInstance();
            background.setCancelButtonText(cancelText);

            background.setIsRetryAvailable(true);
            background.setCancelListener(new Callback() {
                @Override
                public void invoke(Object... args) {
                    inProgress = false;
                    reactErrorCallback.invoke("User cancelled", BiometricPrompt.ERROR_USER_CANCELED);
                }
            });
            background.setRetryButtonText(retryText);
            background.setRetryListener(this);
        }

        // for biometric authentication (NOT PIN code -> will use device credentials)
        if ((BiometricManager.from(activity).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)) {
            Executor executor = ContextCompat.getMainExecutor(activity);
            BiometricPrompt.AuthenticationCallback callback = new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    switch (errorCode) {
                        case BiometricPrompt.ERROR_USER_CANCELED:
                        case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
                        case BiometricPrompt.ERROR_TIMEOUT:
                            //Biometric prompt getting close
                            inProgress = false;
                            break;
                        case BiometricPrompt.ERROR_CANCELED:
                        case BiometricPrompt.ERROR_HW_NOT_PRESENT:
                        case BiometricPrompt.ERROR_HW_UNAVAILABLE:
                        case BiometricPrompt.ERROR_LOCKOUT:
                        case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                        case BiometricPrompt.ERROR_NO_BIOMETRICS:
                        case BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL:
                        case BiometricPrompt.ERROR_NO_SPACE:
                        case BiometricPrompt.ERROR_UNABLE_TO_PROCESS:
                        case BiometricPrompt.ERROR_VENDOR:
                            break;
                    }
                    // if we don't have a background => end auth
                    if (background == null) {
                        inProgress = false;
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();

                    // if we don't have a background => end auth
                    if (background == null) {
                        inProgress = false;
                        // reactErrorCallback.invoke("Authentication failed", BiometricPrompt.ERROR_CANCELED);
                    }
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    inProgress = false;
                    authSuccess = true;
                    authFinished();
                }
            };

            prompt = new BiometricPrompt(activity, executor, callback);

            promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(reason)
                    .setNegativeButtonText(cancelText)
                    .setConfirmationRequired(false)
                    .build();
        }

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (background != null) {
                    background.show(activity.getSupportFragmentManager(), "bg");
                }
            }
        });

        showAuthenticationDialog();
    }

    @TargetApi(Build.VERSION_CODES.M)
    @ReactMethod
    public void authenticate(final String reason, final ReadableMap authConfig, final Callback reactErrorCallback, final Callback reactSuccessCallback) {
        if (!isAppActive) { return; }

        final FragmentActivity activity = (FragmentActivity) getCurrentActivity();
        authenticateOnActivity(activity, reason, authConfig, reactErrorCallback, reactSuccessCallback);
    }

    public boolean hasPendingAuthSuccess() {
        return authSuccess;
    }

    private void authFinished() {
        if (isAppActive && authSuccess) {
            if (background != null) {
                background.dismiss();
                background = null;
            }
            reactSuccessCallback.invoke("Successfully authenticated.");
            authSuccess = false;
        }
    }

    // check is any system security presented on device
    private int isFingerprintAuthAvailable() {
        if (getKeyguardManager() != null)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && getKeyguardManager().isKeyguardSecure()) {
                return FingerprintAuthConstants.NOT_SUPPORTED;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getKeyguardManager().isDeviceSecure()) {
                return FingerprintAuthConstants.IS_SUPPORTED;
            }
        return FingerprintAuthConstants.AUTHENTICATION_FAILED;
    }

    @Override
    public void onHostResume() {
        isAppActive = true;
        authFinished();
    }

    @Override
    public void onHostPause() {
        isAppActive = false;
    }

    @Override
    public void onHostDestroy() {
        isAppActive = false;
    }

    @Override
    public void retry() {
        showAuthenticationDialog();
    }

    private void showAuthenticationDialog() {
        final FragmentActivity activity = (FragmentActivity) getCurrentActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (prompt == null) {
                        showDeviceCredentialActivity(activity, reason, null, confirmDeviceCredentialCode);
                    } else {
                        prompt.authenticate(promptInfo);
                    }
                }
            });
        }
    }
}
