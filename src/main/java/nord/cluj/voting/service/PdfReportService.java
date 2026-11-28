package nord.cluj.voting.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.dto.PollDtos.ResultsView;
import nord.cluj.voting.dto.SessionDtos.SessionView;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.DeletedPoll;
import nord.cluj.voting.entity.PollStatus;
import nord.cluj.voting.entity.SessionStatus;
import nord.cluj.voting.repository.DeletedPollRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The PDF report of one session: every vote that was held in it, including the ones that were deleted later.
 * Works for the running session and for ended ones. Like the CSV export it shows only what any signed-in user
 * can already see (totals only for closed votes, never any names), so anonymous votes stay anonymous.
 */
@Service
public class PdfReportService {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private static final Color INK = new Color(0x1f, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6b, 0x72, 0x80);
    private static final Color HEAD_BG = new Color(0xe5, 0xe7, 0xeb);
    private static final Color GRID = new Color(0xd1, 0xd5, 0xdb);
    private static final Color DELETED_BG = new Color(0xfd, 0xec, 0xec);
    private static final Color DELETED_INK = new Color(0xb9, 0x1c, 0x1c);

    public record PdfFile(String filename, byte[] content) {
    }

    private final SessionService sessions;
    private final PollService polls;
    private final DeletedPollRepository deletedPolls;

    public PdfReportService(SessionService sessions, PollService polls, DeletedPollRepository deletedPolls) {
        this.sessions = sessions;
        this.polls = polls;
        this.deletedPolls = deletedPolls;
    }

    /** One row of the report, whether the vote still exists or was deleted. */
    private record Row(String title, boolean anonymous, boolean deleted, PollStatus status, Instant createdAt,
                       Instant closedAt, Instant deletedAt, int eligible, int voted,
                       Long forCount, Long againstCount, Long abstainCount) {
    }

    @Transactional(readOnly = true)
    public PdfFile sessionPdf(Long sessionId, Long requesterId) {
        SessionView session = sessions.get(sessionId);     // 404 if unknown

        List<Row> rows = new ArrayList<>();
        for (PollView p : polls.list(requesterId, sessionId)) {
            ResultsView r = p.status() == PollStatus.CLOSED ? polls.results(p.id()) : null;
            rows.add(new Row(p.title(), p.anonymous(), false, p.status(), p.createdAt(), p.closedAt(), null,
                    p.eligibleCount(), p.votedCount(),
                    r == null ? null : r.tally().get(Choice.FOR),
                    r == null ? null : r.tally().get(Choice.AGAINST),
                    r == null ? null : r.tally().get(Choice.ABSTAIN)));
        }
        for (DeletedPoll d : deletedPolls.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            rows.add(new Row(d.getTitle(), d.isAnonymous(), true, d.getStatus(), d.getCreatedAt(), d.getClosedAt(),
                    d.getDeletedAt(), d.getEligibleCount(), d.getVotedCount(),
                    d.getForCount(), d.getAgainstCount(), d.getAbstainCount()));
        }
        rows.sort(Comparator.comparing(Row::createdAt));    // oldest first, like minutes of a meeting

        return new PdfFile("session-" + session.id() + "-" + ExportService.slug(session.name()) + ".pdf",
                render(session, rows));
    }

    private byte[] render(SessionView session, List<Row> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 40, 40);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Session report: " + session.name());
            doc.addCreator("Nord Cluj Voting Platform");
            doc.open();

            BaseFont regular = font("fonts/DejaVuSans.ttf");
            BaseFont bold = font("fonts/DejaVuSans-Bold.ttf");
            Font h1 = new Font(bold, 18, Font.NORMAL, INK);
            Font body = new Font(regular, 9.5f, Font.NORMAL, INK);
            Font muted = new Font(regular, 8.5f, Font.NORMAL, MUTED);
            Font th = new Font(bold, 8.5f, Font.NORMAL, INK);
            Font td = new Font(regular, 8.5f, Font.NORMAL, INK);
            Font tdBold = new Font(bold, 8.5f, Font.NORMAL, INK);
            Font tdDeleted = new Font(bold, 8.5f, Font.NORMAL, DELETED_INK);

            doc.add(new Paragraph(session.name(), h1));
            boolean running = session.status() == SessionStatus.ACTIVE;
            doc.add(new Paragraph("Session " + (running ? "in progress" : "ended")
                    + "  ·  started by " + session.startedBy() + " on " + when(session.startedAt())
                    + (running ? "" : "  ·  ended " + when(session.endedAt())), body));
            doc.add(new Paragraph("Generated " + when(Instant.now())
                    + (running ? ", while the session is still running: open votes have no totals yet." : "."), muted));

            long deleted = rows.stream().filter(Row::deleted).count();
            Paragraph summary = new Paragraph(rows.size() + (rows.size() == 1 ? " vote" : " votes")
                    + (deleted > 0 ? " (" + deleted + " deleted)" : ""), body);
            summary.setSpacingBefore(8);
            summary.setSpacingAfter(8);
            doc.add(summary);

            if (rows.isEmpty()) {
                doc.add(new Paragraph("No votes were held in this session.", body));
            } else {
                PdfPTable table = new PdfPTable(new float[]{0.4f, 2.9f, 1.3f, 2.1f, 1.9f, 2.2f, 1.1f, 0.9f, 0.8f, 1.1f, 1.1f});
                table.setWidthPercentage(100);
                table.setHeaderRows(1);
                String[] heads = {"#", "Vote", "Type", "Status", "Created", "Closed / deleted",
                        "Eligible", "Voted", "For", "Against", "Abstain"};
                for (int i = 0; i < heads.length; i++) {
                    table.addCell(cell(heads[i], th, HEAD_BG, i >= 6 ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT));
                }
                int n = 0;
                for (Row r : rows) {
                    n++;
                    Color bg = r.deleted() ? DELETED_BG : null;
                    Font tf = r.deleted() ? tdDeleted : td;
                    String status = r.deleted() ? "DELETED (was " + (r.status() == PollStatus.OPEN ? "open" : "closed") + ")"
                            : r.status() == PollStatus.OPEN ? "Open" : "Closed";
                    String end = r.deleted() ? "deleted " + when(r.deletedAt())
                            : r.closedAt() == null ? "" : when(r.closedAt());
                    table.addCell(cell(String.valueOf(n), td, bg, Element.ALIGN_LEFT));
                    table.addCell(cell(r.title(), r.deleted() ? tdDeleted : tdBold, bg, Element.ALIGN_LEFT));
                    table.addCell(cell(r.anonymous() ? "Anonymous" : "Normal", td, bg, Element.ALIGN_LEFT));
                    table.addCell(cell(status, tf, bg, Element.ALIGN_LEFT));
                    table.addCell(cell(when(r.createdAt()), td, bg, Element.ALIGN_LEFT));
                    table.addCell(cell(end, td, bg, Element.ALIGN_LEFT));
                    table.addCell(cell(String.valueOf(r.eligible()), td, bg, Element.ALIGN_RIGHT));
                    table.addCell(cell(String.valueOf(r.voted()), td, bg, Element.ALIGN_RIGHT));
                    table.addCell(cell(count(r.forCount()), td, bg, Element.ALIGN_RIGHT));
                    table.addCell(cell(count(r.againstCount()), td, bg, Element.ALIGN_RIGHT));
                    table.addCell(cell(count(r.abstainCount()), td, bg, Element.ALIGN_RIGHT));
                }
                doc.add(table);
                Paragraph note = new Paragraph("A dash means there are no totals: the vote was still open when this"
                        + " report was made (totals appear once it closes). Deleted votes stay listed so the record"
                        + " of what was held in the session is complete; their individual votes were erased.", muted);
                note.setSpacingBefore(8);
                doc.add(note);
            }
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Could not build the PDF report", e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    private static PdfPCell cell(String text, Font font, Color background, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        c.setBorderColor(GRID);
        c.setBorder(Rectangle.BOX);
        if (background != null) {
            c.setBackgroundColor(background);
        }
        return c;
    }

    private static String count(Long value) {
        return value == null ? "–" : String.valueOf(value);
    }

    private static String when(Instant instant) {
        return instant == null ? "" : WHEN.format(instant);
    }

    /** Loads a bundled font (a PDF's built-in fonts have no ă, ș, ț) and embeds it in the document. */
    private static BaseFont font(String path) throws IOException, DocumentException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, in.readAllBytes(), null);
        }
    }
}
