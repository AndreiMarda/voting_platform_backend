package nord.cluj.voting.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsernamesTest {

    @Test
    void normalizeTrimsCollapsesAndCapitalises() {
        assertThat(Usernames.normalize("  ion   POPESCU ")).isEqualTo("Ion Popescu");
        assertThat(Usernames.normalize("ana maria ionescu")).isEqualTo("Ana Maria Ionescu");
        assertThat(Usernames.normalize("ana maria\tpopescu")).isEqualTo("Ana Maria Popescu"); // nbsp, tab
        assertThat(Usernames.normalize("ștefan MĂLINA")).isEqualTo("Ștefan Mălina");               // diacritics
    }

    @Test
    void normalizeHandlesNullAndBlank() {
        assertThat(Usernames.normalize(null)).isEmpty();
        assertThat(Usernames.normalize("   ")).isEmpty();
    }

    @Test
    void acceptsTwoOrThreeWordsOfLetters() {
        assertThat(Usernames.isValid("Ion Popescu")).isTrue();
        assertThat(Usernames.isValid("Ana Maria Ionescu")).isTrue();
        assertThat(Usernames.isValid("Alexandru Constantin Dumitrescu")).isTrue();   // 31 chars, longer than the old 30
    }

    @Test
    void rejectsEverythingElse() {
        assertThat(Usernames.isValid("")).isFalse();
        assertThat(Usernames.isValid("Ion")).isFalse();                       // one word
        assertThat(Usernames.isValid("Ana Maria Ioana Popescu")).isFalse();   // four words
        assertThat(Usernames.isValid("Ion Popescu2")).isFalse();              // digit
        assertThat(Usernames.isValid("Ion Pop-escu")).isFalse();              // hyphen
        assertThat(Usernames.isValid("Ion Pop_escu")).isFalse();              // symbol
        assertThat(Usernames.isValid("A".repeat(40) + " " + "B".repeat(40))).isFalse();   // over 60 chars
    }
}
