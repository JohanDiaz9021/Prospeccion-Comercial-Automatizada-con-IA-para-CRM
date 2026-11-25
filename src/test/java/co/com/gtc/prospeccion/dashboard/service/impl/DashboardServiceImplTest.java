package co.com.gtc.prospeccion.dashboard.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO;
import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO.LastRun;
import co.com.gtc.prospeccion.dashboard.persistence.DashboardDAO;
import co.com.gtc.prospeccion.dashboard.persistence.DashboardDAO.RunTotals;

class DashboardServiceImplTest {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    /** 2026-11-01 02:00 UTC = 2026-10-31 21:00 en Colombia: el "hoy" del panel es el de Colombia. */
    static final Clock NOCHE_DEL_31 = Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), BOGOTA);

    private final DashboardDAO dao = mock(DashboardDAO.class);
    private final DashboardServiceImpl service = new DashboardServiceImpl(dao, BOGOTA, NOCHE_DEL_31);

    @BeforeEach
    void preparar() {
        when(dao.findRuns(any(), any(), any())).thenReturn(List.of());
        when(dao.nextScheduledUtc()).thenReturn(Optional.empty());
        when(dao.lastRun()).thenReturn(Optional.empty());
    }

    @Test
    void sinFechasMuestraElMesActualDeColombia() {
        DashboardResponseDTO d = service.get(null, null, null);

        assertThat(d.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(d.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        verify(dao).findRuns(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
    }

    @Test
    void armaElEmbudoLasTasasYLosPendientes() {
        when(dao.findRuns(any(), any(), any())).thenReturn(List.of(new RunTotals(1, 25, 20), new RunTotals(2, 15, 10)));
        when(dao.countSeenResults(List.of(1, 2))).thenReturn(Map.of("CREADO", 18L, "DESCARTADO_IA", 10L,
                "SIN_CORREO", 7L, "EXCLUIDO", 5L));
        when(dao.countLatestDraftStatus(anyCollection())).thenReturn(Map.of("1", 6L, "2", 9L, "3", 2L));
        when(dao.countSendStatus(anyCollection())).thenReturn(Map.of("1", 1L, "2", 8L));
        when(dao.countEventTypes(anyCollection())).thenReturn(Map.of("2", 2L, "4", 1L, "6", 1L));
        when(dao.countDraftsToReview()).thenReturn(7L);
        when(dao.countQueued()).thenReturn(3L);
        when(dao.nextScheduledUtc()).thenReturn(Optional.of(LocalDateTime.of(2026, 11, 3, 12, 0)));
        when(dao.countActiveCampaigns()).thenReturn(2L);
        when(dao.lastRun()).thenReturn(Optional.of(new LastRun(2, "gerentes", "3", LocalDate.of(2026, 10, 30))));

        DashboardResponseDTO d = service.get(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);

        assertThat(d.searchRuns()).isEqualTo(2);
        assertThat(d.funnel()).isEqualTo(new DashboardResponseDTO.Funnel(40, 30, 18, 17, 9, 8, 2, 1));
        assertThat(d.outcomes().discardedByAi()).isEqualTo(10);
        assertThat(d.outcomes().draftsToReview()).isEqualTo(6);
        assertThat(d.outcomes().draftsDiscarded()).isEqualTo(2);
        assertThat(d.outcomes().queued()).isEqualTo(1);
        assertThat(d.outcomes().unsubscribed()).isEqualTo(1);
        assertThat(d.rates().qualification()).isEqualTo(75.0);
        assertThat(d.rates().approval()).isEqualTo(52.9);
        assertThat(d.rates().reply()).isEqualTo(25.0);
        assertThat(d.rates().bounce()).isEqualTo(0.0);
        assertThat(d.pending().draftsToReview()).isEqualTo(7);
        // 12:00 UTC = 7:00 en Colombia.
        assertThat(d.pending().nextScheduled()).isEqualTo(LocalDateTime.of(2026, 11, 3, 7, 0));
        assertThat(d.pending().lastRun().id()).isEqualTo(2);
    }

    @Test
    void sinBaseLasTasasQuedanVaciasNoEnCero() {
        DashboardResponseDTO d = service.get(null, null, 5);

        assertThat(d.rates().qualification()).isNull();
        assertThat(d.rates().reply()).isNull();
        assertThat(d.funnel().processed()).isZero();
        assertThat(d.searchRunId()).isEqualTo(5);
    }

    @Test
    void rechazaPeriodosAlReversOMayoresAUnAno() {
        assertThatThrownBy(() -> service.get(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2), null))
                .isInstanceOf(BusinessException.class);
    }
}
