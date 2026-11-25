package co.com.gtc.prospeccion.tracking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.multitenancy.TenantContext;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeService.Recipient;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeService.Result;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService.UnsubscribeTarget;

class UnsubscribeControllerTest {

    private final UnsubscribeTokenService tokens = mock(UnsubscribeTokenService.class);
    private final UnsubscribeService service = mock(UnsubscribeService.class);
    private final ProspeccionProperties properties = new ProspeccionProperties();
    private UnsubscribeController controller;

    @BeforeEach
    void preparar() {
        properties.setTenantsEnabled(List.of("tenant_demo"));
        when(tokens.verify("bueno")).thenReturn(Optional.of(new UnsubscribeTarget("tenant_demo", 41)));
        when(tokens.verify("otro-tenant")).thenReturn(Optional.of(new UnsubscribeTarget("tenant_apagado", 41)));
        when(tokens.verify("malo")).thenReturn(Optional.empty());
        controller = new UnsubscribeController(tokens, service, properties);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    @Test
    void elGetSoloMuestraElBotonYNoDaDeBaja() {
        when(service.recipientOf(41)).thenReturn(Optional.of(new Recipient("l****@demo.example", false)));

        ResponseEntity<String> r = controller.confirm("bueno");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("l****@demo.example", "Darme de Baja", "method=\"post\"", "?t=bueno");
        verify(service, never()).unsubscribe(any());
        assertThat(r.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void siYaSeHabiaDadoDeBajaLoDice() {
        when(service.recipientOf(41)).thenReturn(Optional.of(new Recipient("l****@demo.example", true)));

        assertThat(controller.confirm("bueno").getBody()).contains("Ya se Había Dado de Baja").doesNotContain("<form");
    }

    @Test
    void elPostDaDeBajaEnElTenantDelTokenYLoSuelta() {
        when(service.unsubscribe(41)).thenAnswer(i -> {
            assertThat(TenantContext.getTenant()).isEqualTo("tenant_demo");
            return Optional.of(new Result("l****@demo.example", true, 0));
        });

        ResponseEntity<String> r = controller.unsubscribe("bueno");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).contains("Listo, se Dio de Baja");
        assertThat(TenantContext.getTenant()).isNull();
    }

    @Test
    void tokenMaloOTenantApagadoEsEnlaceNoValido() {
        assertThat(controller.unsubscribe("malo").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.confirm(null).getBody()).contains("no es Válido");
        assertThat(controller.unsubscribe("otro-tenant").getStatusCode().value()).isEqualTo(400);
        verify(service, never()).unsubscribe(any());
    }

    @Test
    void siFallaLaBaseMuestraIntenteDeNuevo() {
        when(service.unsubscribe(41)).thenThrow(new IllegalStateException("sin base"));

        ResponseEntity<String> r = controller.unsubscribe("bueno");

        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody()).contains("Intente de nuevo");
        assertThat(TenantContext.getTenant()).isNull();
    }

    @Test
    void elHtmlEscapaLoQueMuestra() {
        when(service.recipientOf(41)).thenReturn(Optional.of(new Recipient("<script>@x", false)));

        assertThat(controller.confirm("bueno").getBody()).doesNotContain("<script>").contains("&lt;script&gt;");
    }
}
