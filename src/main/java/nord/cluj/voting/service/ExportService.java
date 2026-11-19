package nord.cluj.voting.service;

import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.dto.PollDtos.ResultsView;
import nord.cluj.voting.dto.SessionDtos.SessionView;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.PollStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Exports a session's results as CSV. Shows exactly what any signed-in user can already see: totals only for
 * closed votes (open votes have blank totals), and never any names, so anonymous votes stay anonymous.
 */
@Service
public class ExportService {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public record CsvFile(String filename, byte[] content) {
    }

    private final SessionService sessions;
    private final PollService polls;

    public ExportService(SessionService sessions, PollService polls) {
        this.sessions = sessions;
        this.polls = polls;
    }

    @Transactional(readOnly = true)
    public CsvFile sessionCsv(Long sessionId, Long requesterId) {
        SessionView session = sessions.get(sessionId);     // 404 if unknown
        List<PollView> list = new ArrayList<>(polls.list(requesterId, sessionId));
        Collections.reverse(list);                          // oldest first, like minutes of a meeting

        StringBuilder csv = new StringBuilder("﻿");   // byte-order mark so Excel reads UTF-8 (diacritics)
        row(csv, "Session", "Vote", "Type", "Status", "Created", "Closed",
                "Eligible voters", "Voted", "For", "Against", "Abstain");
        for (PollView p : list) {
            ResultsView results = p.status() == PollStatus.CLOSED ? polls.results(p.id()) : null;
            row(csv, session.name(), p.title(), p.anonymous() ? "Anonymous" : "Normal",
                    p.status() == PollStatus.OPEN ? "Open" : "Closed",
                    when(p.createdAt()), when(p.closedAt()),
                    String.valueOf(p.eligibleCount()), String.valueOf(p.votedCount()),
                    results == null ? "" : String.valueOf(results.tally().get(Choice.FOR)),
                    results == null ? "" : String.valueOf(results.tally().get(Choice.AGAINST)),
                    results == null ? "" : String.valueOf(results.tally().get(Choice.ABSTAIN)));
        }
        return new CsvFile("session-" + session.id() + "-" + slug(session.name()) + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void row(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells[i]));
        }
        out.append("\r\n");
    }

    /**
     * One CSV cell. Quotes values containing commas, quotes or line breaks, and defuses "formula injection":
     * a value starting with = + - @ would run as a formula in Excel, so it gets a leading apostrophe.
     */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String when(Instant instant) {
        return instant == null ? "" : WHEN.format(instant);
    }

    /** ASCII-only, file-name-safe version of the session name. */
    static String slug(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "session" : slug;
    }
}
