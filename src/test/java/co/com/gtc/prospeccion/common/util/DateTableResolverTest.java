package co.com.gtc.prospeccion.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.repository.DateTableRepository;

class DateTableResolverTest {

    private final DateTableRepository repository = mock(DateTableRepository.class);

    @Test
    void usaLaFechaDeColombiaNoLaDeUtc() {
        // 20 de nov a las 03:00 UTC = 19 de nov a las 22:00 en Bogotá (UTC-5).
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC);
        DateTableET fila = new DateTableET();
        ReflectionTestUtils.setField(fila, "id", 688);
        when(repository.findByDate(LocalDate.of(2026, 11, 19))).thenReturn(Optional.of(fila));

        DateTableResolver resolver = new DateTableResolver(repository, ZoneId.of("America/Bogota"), clock);

        assertThat(resolver.resolveTodayId()).isEqualTo(688);
    }

    @Test
    void siElCalendarioNoTieneLaFechaFallaConUnMensajeClaro() {
        Clock clock = Clock.fixed(Instant.parse("2030-01-01T15:00:00Z"), ZoneOffset.UTC);
        when(repository.findByDate(LocalDate.of(2030, 1, 1))).thenReturn(Optional.empty());

        DateTableResolver resolver = new DateTableResolver(repository, ZoneId.of("America/Bogota"), clock);

        assertThatThrownBy(resolver::today).isInstanceOf(IllegalStateException.class).hasMessageContaining("2030-01-01");
    }
}
