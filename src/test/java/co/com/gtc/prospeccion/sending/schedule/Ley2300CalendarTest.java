package co.com.gtc.prospeccion.sending.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;

/** Ventanas de la Ley 2300 con la configuración por defecto (las mismas de application.yml). */
class Ley2300CalendarTest {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final Ley2300Calendar calendar = new Ley2300Calendar(new ProspeccionProperties());

    static ZonedDateTime bogota(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BOGOTA);
    }

    @Test
    void entreSemanaDe7A19ConElFinExcluido() {
        // Lunes 23 de noviembre de 2026.
        assertThat(calendar.isAllowed(bogota(2026, 11, 23, 6, 59))).isFalse();
        assertThat(calendar.isAllowed(bogota(2026, 11, 23, 7, 0))).isTrue();
        assertThat(calendar.isAllowed(bogota(2026, 11, 23, 18, 59))).isTrue();
        assertThat(calendar.isAllowed(bogota(2026, 11, 23, 19, 0))).isFalse();
    }

    @Test
    void sabadoDe8A15YDomingoNunca() {
        assertThat(calendar.isAllowed(bogota(2026, 11, 28, 7, 30))).isFalse();
        assertThat(calendar.isAllowed(bogota(2026, 11, 28, 8, 0))).isTrue();
        assertThat(calendar.isAllowed(bogota(2026, 11, 28, 14, 59))).isTrue();
        assertThat(calendar.isAllowed(bogota(2026, 11, 28, 15, 0))).isFalse();
        assertThat(calendar.isAllowed(bogota(2026, 11, 29, 10, 0))).isFalse();
    }

    @Test
    void seEvaluaEnHoraDeColombiaAunqueLlegueEnUtc() {
        // 12:00 UTC = 7:00 en Bogotá; 11:59 UTC = 6:59.
        assertThat(calendar.isAllowed(ZonedDateTime.of(2026, 11, 23, 12, 0, 0, 0, ZoneId.of("UTC")))).isTrue();
        assertThat(calendar.isAllowed(ZonedDateTime.of(2026, 11, 23, 11, 59, 0, 0, ZoneId.of("UTC")))).isFalse();
    }

    @Test
    void festivoNuncaAunqueSeaDiaDeSemana() {
        // Lunes 16 de noviembre de 2026: Independencia de Cartagena.
        assertThat(calendar.isAllowed(bogota(2026, 11, 16, 10, 0))).isFalse();
    }

    @Test
    void siguienteVentana() {
        // Antes de abrir: hoy a las 7.
        assertThat(calendar.nextAllowed(bogota(2026, 11, 23, 5, 0))).isEqualTo(bogota(2026, 11, 23, 7, 0));
        // Sábado después de las 15 y domingo: lunes a las 7.
        assertThat(calendar.nextAllowed(bogota(2026, 11, 28, 15, 0))).isEqualTo(bogota(2026, 11, 30, 7, 0));
        assertThat(calendar.nextAllowed(bogota(2026, 11, 29, 10, 0))).isEqualTo(bogota(2026, 11, 30, 7, 0));
        // Sábado 14 de noviembre tarde → domingo → lunes festivo → martes 17 a las 7.
        assertThat(calendar.nextAllowed(bogota(2026, 11, 14, 16, 0))).isEqualTo(bogota(2026, 11, 17, 7, 0));
        // Jueves 24 de diciembre a las 19 → viernes 25 es Navidad → sábado 26 a las 8.
        assertThat(calendar.nextAllowed(bogota(2026, 12, 24, 19, 0))).isEqualTo(bogota(2026, 12, 26, 8, 0));
        // Dentro de la ventana: el mismo instante.
        assertThat(calendar.nextAllowed(bogota(2026, 11, 23, 10, 15))).isEqualTo(bogota(2026, 11, 23, 10, 15));
    }

    @Test
    void diasNoHabilesExtraordinariosPorConfiguracion() {
        ProspeccionProperties properties = new ProspeccionProperties();
        properties.getLey2300().getExtraHolidays().add(LocalDate.of(2026, 11, 24));
        Ley2300Calendar conDecreto = new Ley2300Calendar(properties);

        assertThat(conDecreto.isAllowed(bogota(2026, 11, 24, 10, 0))).isFalse();
        assertThat(conDecreto.nextAllowed(bogota(2026, 11, 23, 19, 0))).isEqualTo(bogota(2026, 11, 25, 7, 0));
    }

    @Test
    void ventanaMalConfiguradaNoArranca() {
        ProspeccionProperties properties = new ProspeccionProperties();
        properties.getLey2300().getWeekdays().setStart("19:00");
        properties.getLey2300().getWeekdays().setEnd("07:00");

        assertThatThrownBy(() -> new Ley2300Calendar(properties)).isInstanceOf(IllegalStateException.class);
    }
}
