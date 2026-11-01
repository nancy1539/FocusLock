package com.example.focuslock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * Pure-Java rules for the Personal User login / register screen.
 *
 * <p>Nothing in here touches Android or Firebase, so everything can be unit tested on the
 * JVM. Validation methods return {@code null} when the value is acceptable, otherwise a short
 * message that is safe to show to the user. Error-message methods translate Firebase error
 * codes into friendly text so raw exception details never reach the UI.
 */
public final class UserAuthRules {

    public static final int MIN_PASSWORD_LENGTH = 6;
    public static final int PHONE_LENGTH = 10;
    public static final int PIN_LENGTH = 4;

    static final String NETWORK_MESSAGE =
            "No internet connection. Check your network and try again";
    static final String GENERIC_MESSAGE = "Something went wrong. Please try again";

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+'-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");

    private UserAuthRules() {
    }

    // ---------------------------------------------------------------- validation

    public static String emailError(String email) {
        if (email == null || email.trim().isEmpty()) {
            return "Enter your email address";
        }
        String e = email.trim();
        if (e.length() > 254 || !EMAIL.matcher(e).matches()) {
            return "Enter a valid email address";
        }
        return null;
    }

    public static String loginPasswordError(String password) {
        if (password == null || password.isEmpty()) {
            return "Enter your password";
        }
        return null;
    }

    public static String registrationPasswordError(String password) {
        if (password == null || password.isEmpty()) {
            return "Enter a password";
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return "Password must be at least " + MIN_PASSWORD_LENGTH + " characters";
        }
        return null;
    }

    public static String parentPhoneError(String phone) {
        if (phone == null || phone.length() != PHONE_LENGTH || !DIGITS.matcher(phone).matches()) {
            return "Enter your parent's " + PHONE_LENGTH + "-digit phone number";
        }
        return null;
    }

    public static String pinError(String pin) {
        if (pin == null || pin.length() != PIN_LENGTH || !DIGITS.matcher(pin).matches()) {
            return "Enter a " + PIN_LENGTH + "-digit PIN";
        }
        return null;
    }

    // ------------------------------------------------------------------- hashing

    /**
     * Lower-case hex SHA-256, byte-for-byte identical to the hash the app has always stored in
     * {@code users/{uid}.pinHash}. Returns {@code null} (never the plain text) if hashing is
     * somehow unavailable.
     */
    public static String sha256Hex(String text) {
        if (text == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ error messages

    /**
     * @param errorCode      {@code FirebaseAuthException.getErrorCode()}, or null if unknown
     * @param networkProblem true when the failure was a {@code FirebaseNetworkException}
     */
    public static String authErrorMessage(String errorCode, boolean networkProblem) {
        if (networkProblem || "ERROR_NETWORK_REQUEST_FAILED".equals(errorCode)) {
            return NETWORK_MESSAGE;
        }
        if (errorCode == null) {
            return GENERIC_MESSAGE;
        }
        switch (errorCode) {
            case "ERROR_INVALID_EMAIL":
                return "That email address is not valid";
            case "ERROR_WRONG_PASSWORD":
            case "ERROR_USER_NOT_FOUND":
            case "ERROR_INVALID_CREDENTIAL":
            case "ERROR_INVALID_LOGIN_CREDENTIALS":
                return "Incorrect email or password";
            case "ERROR_EMAIL_ALREADY_IN_USE":
                return "This email is already registered. Try logging in instead";
            case "ERROR_WEAK_PASSWORD":
                return "That password is too weak. Use at least " + MIN_PASSWORD_LENGTH
                        + " characters";
            case "ERROR_USER_DISABLED":
                return "This account has been disabled";
            case "ERROR_TOO_MANY_REQUESTS":
                return "Too many attempts. Please wait a moment and try again";
            case "ERROR_OPERATION_NOT_ALLOWED":
                return "Email sign-in is not enabled for this app yet. "
                        + "Please contact the app owner";
            default:
                return GENERIC_MESSAGE;
        }
    }

    /**
     * @param codeName {@code FirebaseFirestoreException.getCode().name()}, or null if unknown
     */
    public static String firestoreErrorMessage(String codeName) {
        if (codeName == null) {
            return "Could not load your account profile. Please try again";
        }
        switch (codeName) {
            case "UNAVAILABLE":
            case "DEADLINE_EXCEEDED":
                return NETWORK_MESSAGE;
            case "PERMISSION_DENIED":
            case "UNAUTHENTICATED":
                return "Could not access your account profile. "
                        + "Please try again later or contact the app owner";
            default:
                return "Could not load your account profile. Please try again";
        }
    }
}
