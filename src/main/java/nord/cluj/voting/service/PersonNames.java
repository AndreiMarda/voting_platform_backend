package nord.cluj.voting.service;

import java.util.regex.Pattern;

/**
 * First and last names as people write them: letters, with spaces, hyphens or apostrophes between parts
 * ("Ana Maria", "Popa-Ionescu", "O'Brien"). Normalised before storing: trimmed, single spaces, and every
 * word starts with a capital letter.
 */
public final class PersonNames {

    public static final int MAX_LENGTH = 60;

    public static final String RULE = "First and last names may only contain letters, spaces, hyphens and apostrophes "
            + "(at most " + MAX_LENGTH + " characters each)";

    private static final Pattern VALID = Pattern.compile("\\p{L}+([ '\\-]\\p{L}+)*");
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");

    private PersonNames() {
    }

    /** Trims, collapses whitespace and capitalises the start of each word. Never null; may be empty. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String collapsed = WHITESPACE.matcher(raw).replaceAll(" ").trim();
        StringBuilder out = new StringBuilder();
        boolean wordStart = true;
        for (int i = 0; i < collapsed.length(); ) {
            int c = collapsed.codePointAt(i);
            i += Character.charCount(c);
            out.appendCodePoint(wordStart ? Character.toTitleCase(c) : c);
            wordStart = c == ' ' || c == '-' || c == '\'';
        }
        return out.toString();
    }

    public static boolean isValid(String normalized) {
        return normalized.length() <= MAX_LENGTH && VALID.matcher(normalized).matches();
    }

    /** "Ana Maria Ionescu" -> {"Ana Maria", "Ionescu"}; one word -> {word, ""}. */
    public static String[] splitUsername(String username) {
        int cut = username.lastIndexOf(' ');
        return cut < 0 ? new String[]{username, ""} : new String[]{username.substring(0, cut), username.substring(cut + 1)};
    }
}
