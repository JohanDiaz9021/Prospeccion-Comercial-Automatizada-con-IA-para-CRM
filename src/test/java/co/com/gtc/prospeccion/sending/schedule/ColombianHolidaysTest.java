package co.com.gtc.prospeccion.sending.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ColombianHolidaysTest {

    @Test
    void losFestivosDe2026SonLos18DelCalendarioOficial() {
        assertThat(ColombianHolidays.forYear(2026).keySet()).containsExactly(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 12), LocalDate.of(2026, 3, 23),
                LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 7),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 16), LocalDate.of(2026, 12, 8), LocalDate.of(2026, 12, 25));
        assertThat(ColombianHolidays.forYear(2026)).containsEntry(LocalDate.of(2026, 11, 16), "Independencia de Cartagena");
    }

    @Test
    void losTrasladablesSeCorrenAlLunesYLosFijosNo() {
        // 2027: Reyes cae miércoles 6 → lunes 11; 20 de julio cae martes y no se mueve.
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2027, 1, 11))).isTrue();
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2027, 1, 6))).isFalse();
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2027, 7, 20))).isTrue();
    }

    @Test
    void domingoDePascua() {
        assertThat(ColombianHolidays.easterSunday(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(ColombianHolidays.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(ColombianHolidays.easterSunday(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
    }

    @Test
    void jueves24DeDiciembreNoEsFestivo() {
        assertThat(ColombianHolidays.isHoliday(LocalDate.of(2026, 12, 24))).isFalse();
    }
}
