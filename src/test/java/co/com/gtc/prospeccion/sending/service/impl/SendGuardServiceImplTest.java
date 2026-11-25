package co.com.gtc.prospeccion.sending.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.schedule.Ley2300Calendar;
import co.com.gtc.prospeccion.sending.service.SendGuardService;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

class SendGuardServiceImplTest {

    private final SendQueueRepository queue = mock(SendQueueRepository.class);
    private final TenantConfigRepository configRepository = mock(TenantConfigRepository.class);
    private final SuppressionService suppression = mock(SuppressionService.class);
    private final TenantConfigET config = new TenantConfigET();
    private SendGuardServiceImpl guard;

    static ZonedDateTime bogota(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("America/Bogota"));
    }

    @BeforeEach
    void preparar() {
        when(configRepository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(config));
        when(queue.countScheduledBetween(any(), any(), anyInt(), any())).thenReturn(0L);
        guard = new SendGuardServiceImpl(new Ley2300Calendar(new ProspeccionProperties()), queue, configRepository,
                suppression);
    }

    @Test
    void sinLimitesEnLaConfiguracionUsaLosDeArranque() {
        assertThat(guard.currentLimits().dailyLimit()).isEqualTo(SendGuardService.DEFAULT_DAILY_LIMIT);
        assertThat(guard.currentLimits().spacingSeconds()).isEqualTo(SendGuardService.DEFAULT_SPACING_SECONDS);

        config.setDailySendLimit(35);
        config.setMinSpacingSeconds(90);
        assertThat(guard.currentLimits().dailyLimit()).isEqualTo(35);
        assertThat(guard.currentLimits().spacingSeconds()).isEqualTo(90);
    }

    @Test
    void elDiaSeCuentaEnHoraDeColombia() {
        // 23 de noviembre en Bogotá = de 05:00 UTC del 23 a 05:00 UTC del 24.
        when(queue.countScheduledBetween(eq(LocalDateTime.of(2026, 11, 23, 5, 0)),
                eq(LocalDateTime.of(2026, 11, 24, 5, 0)), anyInt(), eq(SendGuardServiceImpl.OCCUPYING))).thenReturn(7L);

        assertThat(guard.scheduledOn(LocalDate.of(2026, 11, 23))).isEqualTo(7);
    }

    @Test
    void cupoDiarioCuentaSoloLosEnviadosYConCeroEstaPausado() {
        config.setDailySendLimit(2);
        when(queue.countScheduledBetween(any(), any(), anyInt(), eq(SendGuardServiceImpl.SENT))).thenReturn(1L);
        assertThat(guard.hasDailyQuota(bogota(2026, 11, 23, 10, 0))).isTrue();

        when(queue.countScheduledBetween(any(), any(), anyInt(), eq(SendGuardServiceImpl.SENT))).thenReturn(2L);
        assertThat(guard.hasDailyQuota(bogota(2026, 11, 23, 10, 0))).isFalse();

        config.setDailySendLimit(0);
        when(queue.countScheduledBetween(any(), any(), anyInt(), any())).thenReturn(0L);
        assertThat(guard.hasDailyQuota(bogota(2026, 11, 23, 10, 0))).isFalse();
    }

    @Test
    void elSiguienteTurnoSeDevuelveEnUtc() {
        config.setMinSpacingSeconds(120);
        // Último programado: 15:05 UTC = 10:05 Bogotá.
        when(queue.findLastScheduled(anyInt(), any())).thenReturn(LocalDateTime.of(2026, 11, 23, 15, 5));

        assertThat(guard.nextAllowedSlotUtc(bogota(2026, 11, 23, 10, 0))).isEqualTo(LocalDateTime.of(2026, 11, 23, 15, 7));
    }

    @Test
    void laVistaPreviaTieneTope() {
        assertThat(guard.previewSlots(bogota(2026, 11, 23, 10, 0), 10_000)).hasSize(SendGuardServiceImpl.MAX_PREVIEW);
        assertThat(guard.previewSlots(bogota(2026, 11, 23, 10, 0), 0)).hasSize(1);
    }

    @Test
    void laSupresionSeDelegaAlServicio() {
        when(suppression.isSuppressed("ana@empresa-demo.co")).thenReturn(true);

        assertThat(guard.isSuppressed("ana@empresa-demo.co")).isTrue();
        assertThat(guard.isHoliday(LocalDate.of(2026, 12, 25))).isTrue();
        assertThat(guard.isWithinLey2300Window(bogota(2026, 12, 25, 10, 0))).isFalse();
        assertThat(guard.isWithinLey2300Window(bogota(2026, 12, 23, 10, 0))).isTrue();
    }
}
