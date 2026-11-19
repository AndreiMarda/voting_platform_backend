package nord.cluj.voting.controller;

import jakarta.validation.Valid;
import nord.cluj.voting.dto.SessionDtos.SessionView;
import nord.cluj.voting.dto.SessionDtos.StartSessionRequest;
import nord.cluj.voting.security.AuthUser;
import nord.cluj.voting.service.ExportService;
import nord.cluj.voting.service.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService service;
    private final ExportService exports;

    public SessionController(SessionService service, ExportService exports) {
        this.service = service;
        this.exports = exports;
    }

    /** Admin only (see SecurityConfig). The name is mandatory. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView start(@AuthenticationPrincipal AuthUser admin, @Valid @RequestBody StartSessionRequest body) {
        return service.start(body.name(), admin.getId());
    }

    /** Everyone: all sessions, newest first. */
    @GetMapping
    public List<SessionView> list() {
        return service.list();
    }

    /** Everyone: the running session, or 204 No Content if none is running. */
    @GetMapping("/current")
    public ResponseEntity<SessionView> current() {
        return service.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable Long id) {
        return service.get(id);
    }

    /** Everyone: the session's votes and results as a CSV download. Open votes have blank totals. */
    @GetMapping("/{id}/export.csv")
    public ResponseEntity<byte[]> exportCsv(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        ExportService.CsvFile file = exports.sessionCsv(id, user.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(file.content());
    }

    /** Admin only (see SecurityConfig). Closes the session's open votes. */
    @PostMapping("/{id}/end")
    public SessionView end(@PathVariable Long id) {
        return service.end(id);
    }
}
