package com.example.focuslock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for the Personal User login/register rules. */
public class UserAuthRulesTest {

    // ---- email -------------------------------------------------------------

    @Test
    public void validEmailsAreAccepted() {
        assertNull(UserAuthRules.emailError("a@b.co"));
        assertNull(UserAuthRules.emailError("first.last+tag@sub.example.com"));
        assertNull(UserAuthRules.emailError("  padded@example.com  ")); // activity trims too
    }

    @Test
    public void blankOrNullEmailIsRejectedWithPrompt() {
        assertEquals("Enter your email address", UserAuthRules.emailError(null));
        assertEquals("Enter your email address", UserAuthRules.emailError(""));
        assertEquals("Enter your email address", UserAuthRules.emailError("   "));
    }

    @Test
    public void malformedEmailsAreRejected() {
        String[] bad = {"plain", "no-at.example.com", "a@", "@b.com", "a@b", "a@@b.com",
                "a b@c.com", "a@b..com", "a@-b.com", "a@b.com.", "a@.com"};
        for (String s : bad) {
            assertEquals("should reject: " + s, "Enter a valid email address",
                    UserAuthRules.emailError(s));
        }
    }

    @Test
    public void overlongEmailIsRejected() {
        StringBuilder local = new StringBuilder();
        for (int i = 0; i < 250; i++) local.append('a');
        assertEquals("Enter a valid email address",
                UserAuthRules.emailError(local + "@example.com"));
    }

    // ---- password ----------------------------------------------------------

    @Test
    public void loginPasswordOnlyNeedsToBePresent() {
        assertNull(UserAuthRules.loginPasswordError("x"));
        assertNotNull(UserAuthRules.loginPasswordError(""));
        assertNotNull(UserAuthRules.loginPasswordError(null));
    }

    @Test
    public void registrationPasswordNeedsSixCharacters() {
        assertNotNull(UserAuthRules.registrationPasswordError(null));
        assertNotNull(UserAuthRules.registrationPasswordError(""));
        assertEquals("Password must be at least 6 characters",
                UserAuthRules.registrationPasswordError("12345"));
        assertNull(UserAuthRules.registrationPasswordError("123456"));
    }

    // ---- parent phone / PIN --------------------------------------------------

    @Test
    public void parentPhoneMustBeExactlyTenDigits() {
        assertNull(UserAuthRules.parentPhoneError("9876543210"));
        assertNotNull(UserAuthRules.parentPhoneError(null));
        assertNotNull(UserAuthRules.parentPhoneError(""));
        assertNotNull(UserAuthRules.parentPhoneError("987654321"));
        assertNotNull(UserAuthRules.parentPhoneError("98765432101"));
        assertNotNull(UserAuthRules.parentPhoneError("98765abcde"));
        assertNotNull(UserAuthRules.parentPhoneError("+987654321"));
    }

    @Test
    public void pinMustBeExactlyFourDigits() {
        assertNull(UserAuthRules.pinError("1234"));
        assertNull(UserAuthRules.pinError("0000"));
        assertNotNull(UserAuthRules.pinError(null));
        assertNotNull(UserAuthRules.pinError("123"));
        assertNotNull(UserAuthRules.pinError("12345"));
        assertNotNull(UserAuthRules.pinError("12a4"));
    }

    // ---- hashing (must stay identical to what is already stored in Firestore) ----

    @Test
    public void sha256MatchesKnownVectors() {
        assertEquals("03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4",
                UserAuthRules.sha256Hex("1234"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                UserAuthRules.sha256Hex(""));
    }

    @Test
    public void sha256OfNullIsNullNotPlainText() {
        assertNull(UserAuthRules.sha256Hex(null));
    }

    @Test
    public void sha256NeverReturnsThePlainInput() {
        assertNotEquals("1234", UserAuthRules.sha256Hex("1234"));
        assertEquals(64, UserAuthRules.sha256Hex("anything").length());
    }

    // ---- friendly auth messages ------------------------------------------------

    @Test
    public void invalidCredentialCodesAllMapToOneNonRevealingMessage() {
        String expected = "Incorrect email or password";
        assertEquals(expected, UserAuthRules.authErrorMessage("ERROR_WRONG_PASSWORD", false));
        assertEquals(expected, UserAuthRules.authErrorMessage("ERROR_USER_NOT_FOUND", false));
        assertEquals(expected, UserAuthRules.authErrorMessage("ERROR_INVALID_CREDENTIAL", false));
        assertEquals(expected,
                UserAuthRules.authErrorMessage("ERROR_INVALID_LOGIN_CREDENTIALS", false));
    }

    @Test
    public void networkProblemsMapToNetworkMessage() {
        assertEquals(UserAuthRules.NETWORK_MESSAGE, UserAuthRules.authErrorMessage(null, true));
        assertEquals(UserAuthRules.NETWORK_MESSAGE,
                UserAuthRules.authErrorMessage("ERROR_NETWORK_REQUEST_FAILED", false));
        assertEquals(UserAuthRules.NETWORK_MESSAGE, UserAuthRules.firestoreErrorMessage("UNAVAILABLE"));
        assertEquals(UserAuthRules.NETWORK_MESSAGE,
                UserAuthRules.firestoreErrorMessage("DEADLINE_EXCEEDED"));
    }

    @Test
    public void otherKnownAuthCodesHaveSpecificMessages() {
        assertTrue(UserAuthRules.authErrorMessage("ERROR_EMAIL_ALREADY_IN_USE", false)
                .contains("already registered"));
        assertTrue(UserAuthRules.authErrorMessage("ERROR_WEAK_PASSWORD", false)
                .contains("too weak"));
        assertTrue(UserAuthRules.authErrorMessage("ERROR_INVALID_EMAIL", false)
                .contains("not valid"));
        assertTrue(UserAuthRules.authErrorMessage("ERROR_USER_DISABLED", false)
                .contains("disabled"));
        assertTrue(UserAuthRules.authErrorMessage("ERROR_TOO_MANY_REQUESTS", false)
                .contains("Too many attempts"));
        assertTrue(UserAuthRules.authErrorMessage("ERROR_OPERATION_NOT_ALLOWED", false)
                .contains("not enabled"));
    }

    @Test
    public void unknownOrMissingCodesFallBackToGenericMessage() {
        assertEquals(UserAuthRules.GENERIC_MESSAGE, UserAuthRules.authErrorMessage(null, false));
        assertEquals(UserAuthRules.GENERIC_MESSAGE,
                UserAuthRules.authErrorMessage("ERROR_SOMETHING_NEW", false));
    }

    @Test
    public void userFacingMessagesNeverExposeInternalDetails() {
        String[] codes = {null, "ERROR_INTERNAL_ERROR", "ERROR_WRONG_PASSWORD",
                "ERROR_SOMETHING_NEW", "ERROR_WEAK_PASSWORD"};
        for (String c : codes) {
            String m = UserAuthRules.authErrorMessage(c, false);
            assertFalse(m, m.contains("Exception"));
            assertFalse(m, m.contains("ERROR_"));
            assertFalse(m, m.contains("com.google"));
        }
    }

    // ---- friendly firestore messages ----------------------------------------------

    @Test
    public void firestorePermissionErrorsAreFriendly() {
        String m = UserAuthRules.firestoreErrorMessage("PERMISSION_DENIED");
        assertTrue(m.contains("Could not access your account profile"));
        assertEquals(m, UserAuthRules.firestoreErrorMessage("UNAUTHENTICATED"));
    }

    @Test
    public void firestoreUnknownCodesFallBack() {
        String expected = "Could not load your account profile. Please try again";
        assertEquals(expected, UserAuthRules.firestoreErrorMessage(null));
        assertEquals(expected, UserAuthRules.firestoreErrorMessage("NOT_FOUND"));
    }
}
