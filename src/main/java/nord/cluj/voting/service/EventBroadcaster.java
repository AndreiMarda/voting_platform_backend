package nord.cluj.voting.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live updates (Server-Sent Events). Browsers keep one connection open to /api/events; whenever something changes
 * the server sends a small "changed" event and the page re-fetches what it shows.
 * The events carry NO data (only a topic such as "polls"), so they can never leak who voted or what.
 */
@Component
public class EventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

    /** A new browser connects. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);              // 0 = never time out; the browser reconnects if it drops
        Runnable forget = () -> clients.remove(emitter);
        emitter.onCompletion(forget);
        emitter.onTimeout(forget);
        emitter.onError(error -> forget.run());
        clients.add(emitter);
        try {
            emitter.send(SseEmitter.event().name("hello").data("connected"));
        } catch (IOException | IllegalStateException e) {
            forget.run();
        }
        return emitter;
    }

    /**
     * Tells every connected browser that {@code topic} changed ("polls", "session"). Inside a database transaction
     * the event is only sent after it commits (and not at all if it rolls back), so browsers never re-fetch
     * data that is not saved yet.
     */
    public void publish(String topic) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(topic);
                }
            });
        } else {
            send(topic);
        }
    }

    private void send(String topic) {
        for (SseEmitter client : clients) {
            try {
                client.send(SseEmitter.event().name("changed").data(topic));
            } catch (IOException | IllegalStateException e) {
                clients.remove(client);                         // that browser went away
            }
        }
        log.debug("Sent '{}' to {} client(s)", topic, clients.size());
    }

    /** Proxies and browsers close silent connections; a comment line every 25 s keeps them open. */
    @Scheduled(fixedRate = 25_000)
    void keepAlive() {
        for (SseEmitter client : clients) {
            try {
                client.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                clients.remove(client);
            }
        }
    }

    public int clientCount() {
        return clients.size();
    }
}
