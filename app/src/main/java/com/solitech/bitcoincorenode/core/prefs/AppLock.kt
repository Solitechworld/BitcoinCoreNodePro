package com.solitech.bitcoincorenode.core.prefs

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-level lock: a passcode set on first launch, and biometrics on top.
 *
 * ## What this protects, and what it does not
 *
 * Be clear about this, because an app lock invites exactly the wrong mental
 * model. It stops someone who picks up your unlocked phone from opening the
 * app and looking at your balances and addresses. That is worth having.
 *
 * It does **not** encrypt anything. The wallet's private keys are protected by
 * the *wallet passphrase*, which is Bitcoin Core's own encryption, and by
 * nothing else. An attacker with root, or with the device image, can read
 * around this lock — but they still cannot spend without the wallet
 * passphrase.
 *
 * So: this is a privacy screen, not a vault. The UI says so in those words
 * rather than letting people assume the app lock is what is guarding their
 * coins.
 *
 * ## How the passcode is stored
 *
 * PBKDF2-HMAC-SHA256, 210 000 iterations, 16-byte random salt per install,
 * 256-bit output. The passcode itself is never stored, and the comparison is
 * constant-time. The iteration count is deliberately high: the entropy in a
 * 6-digit passcode is tiny, so the only defence against someone who has
 * extracted the hash is making each guess expensive.
 */
@Singleton
class AppLock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {

    enum class State {
        /** No passcode set. The app opens straight in — setup is voluntary. */
        UNCONFIGURED,
        /** Configured, and currently locked. */
        LOCKED,
        /** Unlocked for this session. */
        UNLOCKED,
    }

    private val _state = MutableStateFlow(State.UNCONFIGURED)
    val state: StateFlow<State> = _state.asStateFlow()

    val isConfigured: Flow<Boolean> get() = settings.lockConfigured

    /**
     * Mirrors [settings.isLockConfigured] for the non-suspend paths. Written
     * by [initialise] and [setPasscode]; the only other writer of the store's
     * passcode fields is [clearPasscode], which keeps this in step.
     */
    private var configured = false

    /**
     * Called once at startup to decide which screen to show.
     *
     * No passcode, no gate. A first-launch "set a passcode" wall reads as a
     * requirement, blocks the user who just wants to look around first, and
     * put an extra keypad between every fresh install and their wallet.
     * Setting one later is one tap away in Settings → Security.
     */
    suspend fun initialise() {
        configured = settings.isLockConfigured()
        _state.value = if (configured) State.LOCKED else State.UNLOCKED
    }

    /**
     * Sets the first passcode.
     *
     * @return null on success, or a reason to show the user.
     */
    suspend fun setPasscode(passcode: String): String? {
        validate(passcode)?.let { return it }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = withContext(Dispatchers.Default) { derive(passcode, salt) }
        settings.setPasscode(hash.toHex(), salt.toHex())
        configured = true
        _state.value = State.UNLOCKED
        return null
    }

    /**
     * Removes the passcode entirely. Requires the current one — otherwise the
     * first hand that grabs an unlocked phone could strip the lock.
     *
     * @return null on success, or a reason to show the user.
     */
    suspend fun clearPasscode(current: String): String? {
        if (!verify(current)) return "That passcode is not correct."
        settings.clearPasscode()
        configured = false
        _state.value = State.UNCONFIGURED
        return null
    }

    suspend fun changePasscode(current: String, next: String): String? {
        if (!verify(current)) return "That passcode is not correct."
        validate(next)?.let { return it }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = withContext(Dispatchers.Default) { derive(next, salt) }
        settings.setPasscode(hash.toHex(), salt.toHex())
        return null
    }

    /** Constant-time verification. Deliberately slow — see the class comment. */
    suspend fun verify(passcode: String): Boolean {
        val storedHash = settings.passcodeHash() ?: return false
        val storedSalt = settings.passcodeSalt() ?: return false
        val computed = withContext(Dispatchers.Default) {
            derive(passcode, storedSalt.fromHex())
        }
        // MessageDigest.isEqual is the constant-time comparison on the JVM.
        // A plain contentEquals leaks how many leading bytes matched via timing.
        return MessageDigest.isEqual(computed, storedHash.fromHex())
    }

    fun unlock() { _state.value = State.UNLOCKED }

    /**
     * Re-locks. Called when the app goes to background, so leaving it in the
     * recents list and coming back an hour later does not hand someone an
     * unlocked wallet.
     *
     * Only when a passcode exists: locking an unconfigured app is a dead end
     * — there would be nothing to verify against.
     */
    fun lock() {
        if (configured && _state.value == State.UNLOCKED) _state.value = State.LOCKED
    }

    /** Whether this device can prompt for biometrics or a device credential. */
    fun biometricAvailability(): BiometricAvailability {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.NO_HARDWARE
            else -> BiometricAvailability.UNAVAILABLE
        }
    }

    private fun validate(passcode: String): String? = when {
        passcode.length < MIN_LENGTH ->
            "Use at least $MIN_LENGTH characters."
        passcode.all { it == passcode.first() } ->
            "Don't use the same character repeated."
        passcode in WEAK ->
            "That passcode is one of the most common ones. Pick another."
        else -> null
    }

    private fun derive(passcode: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = ByteArray(length / 2) {
        substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    enum class BiometricAvailability { AVAILABLE, NONE_ENROLLED, NO_HARDWARE, UNAVAILABLE }

    companion object {
        private const val ITERATIONS = 210_000
        private const val KEY_BITS = 256
        private const val SALT_BYTES = 16
        const val MIN_LENGTH = 6

        /**
         * Rejected outright. Not a serious defence — it is a handful of entries
         * against an enormous space — but these few account for a
         * disproportionate share of real passcodes, and refusing them costs the
         * user one extra tap.
         */
        private val WEAK = setOf(
            "123456", "654321", "123123", "112233", "121212",
            "000000", "111111", "222222", "333333", "444444",
            "555555", "666666", "777777", "888888", "999999",
            "123321", "159753", "147258", "258369", "369258",
        )
    }
}
