package co.com.gtc.prospeccion.tracking.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.multitenancy.TenantContext;
import co.com.gtc.prospeccion.tracking.service.UnsubscribeTokenService.UnsubscribeTarget;

class UnsubscribeTokenServiceImplTest {

    static final String SECRETO = "secreto-de-prueba-para-firmar-enlaces-0001";

    private final UnsubscribeTokenServiceImpl service =
            new UnsubscribeTokenServiceImpl(() -> SECRETO, "https://crm.example/msa-prospeccion/public/unsubscribe");

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    @Test
    void elTokenLlevaElTenantYElEnvioYSeVerifica() {
        TenantContext.setTenant("tenant_demo");

        String token = service.createToken(41);

        assertThat(token).doesNotContain("tenant_demo").doesNotContain("=").contains(".");
        assertThat(service.verify(token)).contains(new UnsubscribeTarget("tenant_demo", 41));
    }

    @Test
    void elEnlaceUsaLaUrlPublica() {
        TenantContext.setTenant("tenant_demo");

        assertThat(service.createUrl(41)).startsWith("https://crm.example/msa-prospeccion/public/unsubscribe?t=");
    }

    @Test
    void unTokenAlteradoNoSirve() {
        TenantContext.setTenant("tenant_demo");
        String token = service.createToken(41);
        TenantContext.setTenant("tenant_demo");
        String otro = service.createToken(42);

        // Cambiar el envío (o el tenant) sin la firma correspondiente.
        String alterado = otro.substring(0, otro.indexOf('.')) + token.substring(token.indexOf('.'));
        assertThat(service.verify(alterado)).isEmpty();
        assertThat(service.verify(token + "x")).isEmpty();
    }

    @Test
    void conOtroSecretoNoSirve() {
        TenantContext.setTenant("tenant_demo");
        String token = service.createToken(41);
        UnsubscribeTokenServiceImpl otroSistema = new UnsubscribeTokenServiceImpl(
                () -> "otro-secreto-distinto-de-al-menos-32-caracteres", "https://x.example");

        assertThat(otroSistema.verify(token)).isEmpty();
    }

    @Test
    void basuraNoLanzaError() {
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("sin-punto")).isEmpty();
        assertThat(service.verify("%%%.###")).isEmpty();
        assertThat(service.verify("a.b.c")).isEmpty();
        assertThat(service.verify("x".repeat(600))).isEmpty();
    }

    @Test
    void sinTenantOConSecretoCortoNoFirma() {
        assertThatThrownBy(() -> service.createToken(41)).isInstanceOf(IllegalStateException.class);

        TenantContext.setTenant("tenant_demo");
        UnsubscribeTokenServiceImpl corto = new UnsubscribeTokenServiceImpl(() -> "corto", "https://x.example");
        assertThatThrownBy(() -> corto.createToken(41)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void elPixelLlevaOtraFirmaQueElEnlaceDeBaja() {
        TenantContext.setTenant("tenant_demo");
        UnsubscribeTokenServiceImpl conPixel = new UnsubscribeTokenServiceImpl(() -> SECRETO,
                "https://crm.example/msa-prospeccion/public/unsubscribe", "https://crm.example/msa-prospeccion/public/open");

        String url = conPixel.createOpenPixelUrl(41);
        String token = url.substring(url.lastIndexOf('/') + 1, url.length() - ".gif".length());

        assertThat(url).startsWith("https://crm.example/msa-prospeccion/public/open/").endsWith(".gif");
        assertThat(conPixel.verifyOpen(token)).contains(new UnsubscribeTarget("tenant_demo", 41));
        // Un token de píxel no sirve para dar de baja, ni uno de baja como píxel.
        assertThat(conPixel.verify(token)).isEmpty();
        assertThat(conPixel.verifyOpen(conPixel.createToken(41))).isEmpty();
        assertThatThrownBy(() -> new UnsubscribeTokenServiceImpl(() -> SECRETO, "https://x.example").createOpenPixelUrl(41))
                .isInstanceOf(IllegalStateException.class);
    }
}
