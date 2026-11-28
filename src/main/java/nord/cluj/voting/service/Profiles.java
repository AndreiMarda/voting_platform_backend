package nord.cluj.voting.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Rules for the personal details a user fills in: email address and ID ONCR. */
public final class Profiles {

    public static final String ID_RULE = "The ID ONCR must be two capital letters followed by digits, "
            + "at most 7 characters in total, e.g. AB12345";
    public static final String EMAIL_RULE = "Enter a valid email address";

    private static final Pattern ID = Pattern.compile("[A-Z]{2}[0-9]{1,5}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private Profiles() {
    }

    /** Trimmed and upper-cased, so "ab123" and "AB123" are the same ID. Never null; may be empty. */
    public static String normalizeId(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isValidId(String normalized) {
        return ID.matcher(normalized).matches();
    }

    /** Trimmed and lower-cased. Never null; may be empty. */
    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidEmail(String normalized) {
        return normalized.length() <= 254 && EMAIL.matcher(normalized).matches();
    }
}
