package android.media.audiopolicy;

import android.media.AudioFocusInfo;

/**
 * Compile-only @SystemApi stub for {@code android.media.audiopolicy.AudioPolicy.AudioPolicyFocusListener}.
 * Never packaged into the APK DEX; resolved against Android framework at runtime.
 */
public class AudioPolicy {
    public static abstract class AudioPolicyFocusListener {
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {}

        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {}

        public void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {}

        public void onAudioFocusAbandon(AudioFocusInfo afi) {}
    }
}
