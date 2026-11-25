package co.com.gtc.prospeccion.integration.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageIdsTest {

    @Test
    void cadaEnvioTieneSuMessageIdYSeRecuperaDeLosEncabezados() {
        String id = MessageIds.forSend(41, "prospeccion.example");

        assertThat(id).matches("<pia\\.41\\.[a-z0-9]{12}@prospeccion\\.example>");
        assertThat(MessageIds.forSend(41, "prospeccion.example")).isNotEqualTo(id);
        assertThat(MessageIds.findSendId(null, "<otro@x.example> " + id)).contains(41);
        assertThat(MessageIds.findSendId("Message-ID: " + id + "\nTo: ana@clinica.example")).contains(41);
    }

    @Test
    void sinNuestroMessageIdNoHayEnvio() {
        assertThat(MessageIds.findSendId("<CAF=abc@mail.gmail.com>", null, "<pia.x.abc@y>")).isEmpty();
        assertThatThrownBy(() -> MessageIds.forSend(0, "x.example")).isInstanceOf(IllegalArgumentException.class);
    }
}
