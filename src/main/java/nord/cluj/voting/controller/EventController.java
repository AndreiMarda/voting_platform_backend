package nord.cluj.voting.controller;

import nord.cluj.voting.service.EventBroadcaster;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventBroadcaster events;

    public EventController(EventBroadcaster events) {
        this.events = events;
    }

    /** Any signed-in user. An open stream of "changed" events; see EventBroadcaster. */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return events.subscribe();
    }
}
