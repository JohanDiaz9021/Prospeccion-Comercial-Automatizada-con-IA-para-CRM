package co.com.gtc.prospeccion.sending.schedule;

import static co.com.gtc.prospeccion.sending.schedule.Ley2300CalendarTest.bogota;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.sending.schedule.SendSlotPlanner.Limits;

class SendSlotPlannerTest {

    private final Ley2300Calendar calendar = new Ley2300Calendar(new ProspeccionProperties());

    @Test
    void sinColaSaleYaSiEstaDentroDeLaVentana() {
        ZonedDateTime ahora = bogota(2026, 11, 23, 10, 0);

        assertThat(SendSlotPlanner.next(calendar, ahora, null, new Limits(20, 120), d -> 0)).isEqualTo(ahora);
    }

    @Test
    void respetaElEspaciadoDesdeElUltimoProgramado() {
        ZonedDateTime ahora = bogota(2026, 11, 23, 10, 0);
        ZonedDateTime ultimo = bogota(2026, 11, 23, 10, 5);

        assertThat(SendSlotPlanner.next(calendar, ahora, ultimo, new Limits(20, 120), d -> 1))
                .isEqualTo(bogota(2026, 11, 23, 10, 7));
    }

    @Test
    void siElEspaciadoPasaDeLas19VaAlDiaHabilSiguiente() {
        ZonedDateTime ultimo = bogota(2026, 11, 27, 18, 59); // viernes

        assertThat(SendSlotPlanner.next(calendar, bogota(2026, 11, 27, 18, 0), ultimo, new Limits(20, 120), d -> 1))
                .isEqualTo(bogota(2026, 11, 28, 8, 0)); // sábado a las 8
    }

    @Test
    void conElLimiteDiarioLlenoPasaAlDiaHabilSiguiente() {
        Map<LocalDate, Integer> programados = Map.of(LocalDate.of(2026, 11, 28), 5); // sábado lleno

        ZonedDateTime turno = SendSlotPlanner.next(calendar, bogota(2026, 11, 28, 9, 0), null, new Limits(5, 60),
                d -> programados.getOrDefault(d, 0));

        assertThat(turno).isEqualTo(bogota(2026, 11, 30, 7, 0)); // lunes
    }

    @Test
    void planDeVariosCorreosConLimiteYEspaciado() {
        // Viernes 18:50, límite 2 por día, 5 minutos entre correos.
        List<ZonedDateTime> turnos = SendSlotPlanner.plan(calendar, bogota(2026, 11, 27, 18, 50), null,
                new Limits(2, 300), d -> 0, 5);

        assertThat(turnos).containsExactly(
                bogota(2026, 11, 27, 18, 50),
                bogota(2026, 11, 27, 18, 55), // viernes lleno (2)
                bogota(2026, 11, 28, 8, 0),   // 18:55 + 5 min = 19:00, ya cerrado → sábado a las 8
                bogota(2026, 11, 28, 8, 5),   // sábado lleno
                bogota(2026, 11, 30, 7, 0));  // domingo no → lunes
    }

    @Test
    void sinLimiteDiarioSoloAplicaVentanaYEspaciado() {
        List<ZonedDateTime> turnos = SendSlotPlanner.plan(calendar, bogota(2026, 11, 23, 7, 0), null,
                new Limits(0, 60), d -> 1000, 3);

        assertThat(turnos).containsExactly(bogota(2026, 11, 23, 7, 0), bogota(2026, 11, 23, 7, 1),
                bogota(2026, 11, 23, 7, 2));
    }
}
