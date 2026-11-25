package co.com.gtc.prospeccion.integration.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.integration.mailbox.dto.IncomingMessage;

class SmtpMailboxAdapterTest {

    private FakeSmtp smtp;
    private HttpServer mailpit;

    @AfterEach
    void cerrar() throws IOException {
        if (smtp != null) {
            smtp.close();
        }
        if (mailpit != null) {
            mailpit.stop(0);
        }
    }

    private static ProspeccionProperties properties(String host, int port) {
        ProspeccionProperties p = new ProspeccionProperties();
        p.getMailbox().setFromAddress("prospeccion@gtc-prueba.local");
        p.getMailbox().getSmtp().setHost(host);
        p.getMailbox().getSmtp().setPort(port);
        return p;
    }

    @Test
    void porSeguridadNoArrancaConUnServidorQueNoEsLocal() {
        assertThatThrownBy(() -> new SmtpMailboxAdapter(properties("smtp.office365.com", 587), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SMTP_ALLOW_REMOTE_HOST");
        assertThatThrownBy(() -> new SmtpMailboxAdapter(properties(" ", 1025), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SMTP_HOST");
    }

    @Test
    void soloSonLocalesLocalhostLoopbackYLosNombresDeMailpit() {
        assertThat(SmtpMailboxAdapter.isLocal("localhost")).isTrue();
        assertThat(SmtpMailboxAdapter.isLocal("127.0.0.1")).isTrue();
        assertThat(SmtpMailboxAdapter.isLocal("mailpit")).isTrue();
        assertThat(SmtpMailboxAdapter.isLocal("192.168.1.20")).isFalse(); // podría ser un servidor real de la red
        assertThat(SmtpMailboxAdapter.isLocal("smtp.gmail.com")).isFalse();
    }

    @Test
    void conPermisoExplicitoAceptaOtroServidor() {
        ProspeccionProperties p = properties("smtp.office365.com", 587);
        p.getMailbox().getSmtp().setAllowRemoteHost(true);

        assertThat(new SmtpMailboxAdapter(p, new ObjectMapper()).requiresMailboxKey()).isFalse();
    }

    @Test
    void enviaPorSmtpConNuestrosEncabezados() throws Exception {
        smtp = new FakeSmtp();
        SmtpMailboxAdapter adapter = new SmtpMailboxAdapter(properties("127.0.0.1", smtp.port()), new ObjectMapper());

        String id = adapter.send(MimeEmailsTest.correo());

        assertThat(id).isEqualTo(MimeEmailsTest.NUESTRO_ID);
        assertThat(smtp.recipients).containsExactly("Ana.Perez@Clinica-Demo.example");
        String raw = smtp.messages.get(0);
        assertThat(raw).contains("Message-ID: " + MimeEmailsTest.NUESTRO_ID).contains("List-Unsubscribe-Post: List-Unsubscribe=One-Click");
        IncomingMessage leido = MimeEmails.parse(raw.getBytes(StandardCharsets.UTF_8), "x", null, null);
        assertThat(leido.getBodyText()).contains("Le escribo por integración.");
    }

    @Test
    void unaDireccionRechazadaEsPermanenteYUnServidorCaidoEsTemporal() throws Exception {
        smtp = new FakeSmtp();
        SmtpMailboxAdapter adapter = new SmtpMailboxAdapter(properties("127.0.0.1", smtp.port()), new ObjectMapper());
        var rechazado = MimeEmailsTest.correo();
        rechazado.setTo("rechazado@clinica-demo.example");

        assertThatThrownBy(() -> adapter.send(rechazado))
                .isInstanceOfSatisfying(MailboxSendException.class, e -> assertThat(e.isPermanent()).isTrue());

        int libre;
        try (ServerSocket s = new ServerSocket(0)) {
            libre = s.getLocalPort();
        }
        SmtpMailboxAdapter caido = new SmtpMailboxAdapter(properties("127.0.0.1", libre), new ObjectMapper());
        assertThatThrownBy(() -> caido.send(MimeEmailsTest.correo()))
                .isInstanceOfSatisfying(MailboxSendException.class, e -> assertThat(e.isPermanent()).isFalse());
    }

    @Test
    void leeDeMailpitSoloLoQueLlegoAlBuzonDesdeLaFecha() throws Exception {
        String respuesta = """
                From: ana.perez@clinica-demo.example
                To: prospeccion@gtc-prueba.local
                Subject: RE: hola
                Message-ID: <r1@clinica-demo.example>
                In-Reply-To: <pia.42.abc123def456@gtc-prueba.local>
                Content-Type: text/plain; charset=utf-8

                Me interesa.
                """.replace("\n", "\r\n");
        String lista = """
                {"total": 3, "messages": [
                  {"ID": "nuevo", "To": [{"Name": "", "Address": "Prospeccion@GTC-Prueba.local"}], "Created": "2026-11-24T10:00:00.000-05:00"},
                  {"ID": "viejo", "To": [{"Address": "prospeccion@gtc-prueba.local"}], "Created": "2026-11-01T10:00:00Z"},
                  {"ID": "salida", "To": [{"Address": "ana.perez@clinica-demo.example"}], "Created": "2026-11-24T15:00:00Z"}
                ]}""";
        mailpit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mailpit.createContext("/api/v1/messages", ex -> responder(ex, lista));
        mailpit.createContext("/api/v1/message/nuevo/raw", ex -> responder(ex, respuesta));
        mailpit.start();
        ProspeccionProperties p = properties("localhost", 1025);
        p.getMailbox().getSmtp().setMailpitApiUrl("http://127.0.0.1:" + mailpit.getAddress().getPort() + "/");
        SmtpMailboxAdapter adapter = new SmtpMailboxAdapter(p, new ObjectMapper());

        List<IncomingMessage> leidos = adapter.readInbox(null, LocalDateTime.of(2026, 11, 20, 0, 0));

        assertThat(leidos).hasSize(1);
        assertThat(leidos.get(0).getProviderMessageId()).isEqualTo("mailpit-nuevo");
        assertThat(leidos.get(0).getReceivedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 15, 0));
        assertThat(leidos.get(0).getInReplyTo()).isEqualTo("<pia.42.abc123def456@gtc-prueba.local>");
    }

    @Test
    void sinApiDeMailpitNoHayBuzonQueLeer() {
        assertThat(new SmtpMailboxAdapter(properties("localhost", 1025), new ObjectMapper())
                .readInbox(null, LocalDateTime.now())).isEmpty();
    }

    private static void responder(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Servidor SMTP mínimo: acepta todo menos los destinatarios que empiezan por "rechazado". */
    static final class FakeSmtp implements AutoCloseable {
        final List<String> recipients = new CopyOnWriteArrayList<>();
        final List<String> messages = new CopyOnWriteArrayList<>();
        private final ServerSocket server;
        private final Thread thread;

        FakeSmtp() throws IOException {
            server = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "fake-smtp");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept();
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                    out.print("220 fake\r\n");
                    out.flush();
                    String line;
                    while ((line = in.readLine()) != null) {
                        String upper = line.toUpperCase();
                        if (upper.startsWith("RCPT TO:")) {
                            String address = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                            if (address.startsWith("rechazado")) {
                                out.print("550 5.1.1 User unknown\r\n");
                            } else {
                                recipients.add(address);
                                out.print("250 OK\r\n");
                            }
                        } else if (upper.equals("DATA")) {
                            out.print("354 go\r\n");
                            out.flush();
                            StringBuilder data = new StringBuilder();
                            String d;
                            while ((d = in.readLine()) != null && !d.equals(".")) {
                                data.append(d.startsWith("..") ? d.substring(1) : d).append("\r\n");
                            }
                            messages.add(data.toString());
                            out.print("250 queued\r\n");
                        } else if (upper.equals("QUIT")) {
                            out.print("221 bye\r\n");
                            out.flush();
                            break;
                        } else {
                            out.print("250 OK\r\n");
                        }
                        out.flush();
                    }
                } catch (IOException e) {
                    // servidor cerrado
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
