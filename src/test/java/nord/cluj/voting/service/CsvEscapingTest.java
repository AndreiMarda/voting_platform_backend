package nord.cluj.voting.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvEscapingTest {

    @Test
    void plainTextIsLeftAlone() {
        assertThat(ExportService.cell("Budget 2027")).isEqualTo("Budget 2027");
        assertThat(ExportService.cell("Ședința de mâine")).isEqualTo("Ședința de mâine");
        assertThat(ExportService.cell(null)).isEmpty();
    }

    @Test
    void commasQuotesAndLineBreaksAreQuoted() {
        assertThat(ExportService.cell("Budget, 2027")).isEqualTo("\"Budget, 2027\"");
        assertThat(ExportService.cell("Say \"yes\"")).isEqualTo("\"Say \"\"yes\"\"\"");
        assertThat(ExportService.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }

    @Test
    void formulasAreDefusedSoExcelDoesNotRunThem() {
        assertThat(ExportService.cell("=SUM(A1:A9)")).isEqualTo("'=SUM(A1:A9)");
        assertThat(ExportService.cell("+1")).isEqualTo("'+1");
        assertThat(ExportService.cell("-2")).isEqualTo("'-2");
        assertThat(ExportService.cell("@cmd")).isEqualTo("'@cmd");
        assertThat(ExportService.cell("=1,2")).isEqualTo("\"'=1,2\"");               // both rules together
        assertThat(ExportService.cell("a=b")).isEqualTo("a=b");                      // only a LEADING = is dangerous
    }

    @Test
    void fileNamesAreAsciiAndSafe() {
        assertThat(ExportService.slug("Adunarea Generală de toamnă 2026")).isEqualTo("adunarea-generala-de-toamna-2026");
        assertThat(ExportService.slug("Ședința #1 / \"urgent\"")).isEqualTo("sedinta-1-urgent");
        assertThat(ExportService.slug("???")).isEqualTo("session");
        assertThat(ExportService.slug("a".repeat(100))).hasSize(40);
    }
}
