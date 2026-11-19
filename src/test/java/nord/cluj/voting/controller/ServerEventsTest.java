package nord.cluj.voting.controller;

import nord.cluj.voting.service.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deliberately NOT @Transactional: events are only sent after a transaction commits, so these tests control the
 * transactions themselves.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServerEventsTest {

    @Autowired MockMvc mvc;
    @Autowired EventBroadcaster events;
    @Autowired PlatformTransactionManager transactions;

    private MvcResult connect() throws Exception {
        return mvc.perform(get("/api/events").accept("text/event-stream"))
                .andExpect(request().asyncStarted()).andReturn();
    }

    @Test
    @WithMockUser
    void aSignedInBrowserReceivesChangeEvents() throws Exception {
        MvcResult stream = connect();
        assertThat(stream.getResponse().getContentAsString()).contains("event:hello");

        events.publish("polls");

        assertThat(stream.getResponse().getContentAsString()).contains("event:changed").contains("data:polls");
    }

    @Test
    void anonymousCallersCannotListen() throws Exception {
        mvc.perform(get("/api/events").accept("text/event-stream")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void everyConnectedBrowserGetsTheEvent() throws Exception {
        MvcResult first = connect();
        MvcResult second = connect();

        events.publish("session");

        assertThat(first.getResponse().getContentAsString()).contains("data:session");
        assertThat(second.getResponse().getContentAsString()).contains("data:session");
    }

    @Test
    @WithMockUser
    void insideATransactionTheEventWaitsForTheCommit() throws Exception {
        MvcResult stream = connect();

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            events.publish("polls");
            // not committed yet: the browser must not be told to re-fetch data that is not saved
            try {
                assertThat(stream.getResponse().getContentAsString()).doesNotContain("data:polls");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(stream.getResponse().getContentAsString()).contains("data:polls");
    }

    @Test
    @WithMockUser
    void aRolledBackTransactionSendsNothing() throws Exception {
        MvcResult stream = connect();

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            events.publish("rolled-back-topic");
            tx.setRollbackOnly();
        });

        assertThat(stream.getResponse().getContentAsString()).doesNotContain("rolled-back-topic");
    }

    @Test
    @WithMockUser
    void theEventNeverCarriesAnythingButATopic() throws Exception {
        MvcResult stream = connect();

        events.publish("polls");

        String body = stream.getResponse().getContentAsString();
        assertThat(body.lines().filter(l -> l.startsWith("data:")).map(String::trim).toList())
                .containsOnly("data:connected", "data:polls");
    }
}
