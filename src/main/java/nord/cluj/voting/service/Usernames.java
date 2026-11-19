package nord.cluj.voting.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A username is a person's name: one or two first names followed by a last name (2 or 3 words, letters
 * only). It is normalised before it is checked, stored or looked up: surrounding/extra spaces are removed
 * and every word starts with a capital letter, so "  ion   POPESCU " and "Ion Popescu" are the same user.
 */
public final class Usernames {

    public static final int MAX_LENGTH = 60;

    public static final String RULE = "The username must be one or two first names followed by a last name "
            + "(2 or 3 words, letters only), e.g. \"Ion Popescu\" or \"Ana Maria Ionescu\"";

    private static final Pattern VALID = Pattern.compile("\\p{L}+( \\p{L}+){1,2}");
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");

    private Usernames() {
    }

    /** Trims, collapses whitespace and capitalises each word. Never null; may be empty. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String collapsed = WHITESPACE.matcher(raw).replaceAll(" ").trim();
        if (collapsed.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String word : collapsed.split(" ")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            int first = word.codePointAt(0);
            out.append(Character.toString(Character.toTitleCase(first)))
               .append(word.substring(Character.charCount(first)).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** @param normalized the result of {@link #normalize(String)} */
    public static boolean isValid(String normalized) {
        return normalized.length() <= MAX_LENGTH && VALID.matcher(normalized).matches();
    }
}
