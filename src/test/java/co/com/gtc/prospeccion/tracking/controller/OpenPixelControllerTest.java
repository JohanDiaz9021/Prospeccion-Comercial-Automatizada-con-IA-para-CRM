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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.multitenancy.TenantContext;
import co.com.gtc.prospeccion.tracking.service.TrackingService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService.UnsubscribeTarget;

class OpenPixelControllerTest {

    private final UnsubscribeTokenService tokens = mock(UnsubscribeTokenService.class);
    private final TrackingService tracking = mock(TrackingService.class);
    private final ProspeccionProperties properties = new ProspeccionProperties();
    private OpenPixelController controller;

    @BeforeEach
    void preparar() {
        properties.setTenantsEnabled(List.of("tenant_demo"));
        when(tokens.verifyOpen("bueno")).thenReturn(Optional.of(new UnsubscribeTarget("tenant_demo", 41)));
        when(tokens.verifyOpen("otro-tenant")).thenReturn(Optional.of(new UnsubscribeTarget("tenant_apagado", 41)));
        when(tokens.verifyOpen("malo")).thenReturn(Optional.empty());
        controller = new OpenPixelController(tokens, tracking, properties);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    @Test
    void apagadoPorDefectoDevuelveLaImagenSinRegistrarNada() {
        ResponseEntity<byte[]> r = controller.open("bueno.gif");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_GIF);
        assertThat(r.getBody()).startsWith((byte) 'G', (byte) 'I', (byte) 'F');
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
        verify(tracking, never()).registerOpen(any());
    }

    @Test
    void encendidoRegistraEnElTenantDelTokenYLoSuelta() {
        properties.getTracking().getOpenPixel().setEnabled(true);
        when(tracking.registerOpen(41)).thenAnswer(i -> {
            assertThat(TenantContext.getTenant()).isEqualTo("tenant_demo");
            return true;
        });

        controller.open("bueno.gif");

        verify(tracking).registerOpen(41);
        assertThat(TenantContext.getTenant()).isNull();
    }

    @Test
    void tokenMaloTenantApagadoOErrorSiempreDevuelvenLaImagen() {
        properties.getTracking().getOpenPixel().setEnabled(true);
        when(tokens.verifyOpen("roto")).thenThrow(new IllegalStateException("sin secreto"));

        assertThat(controller.open("malo.gif").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.open("otro-tenant.gif").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.open("roto.gif").getStatusCode().value()).isEqualTo(200);
        verify(tracking, never()).registerOpen(any());
    }
}
