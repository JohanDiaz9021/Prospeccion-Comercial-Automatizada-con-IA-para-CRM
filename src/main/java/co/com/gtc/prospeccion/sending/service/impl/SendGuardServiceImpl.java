package co.com.gtc.prospeccion.sending.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.sending.repository.SendQueueRepository;
import co.com.gtc.prospeccion.sending.schedule.Ley2300Calendar;
import co.com.gtc.prospeccion.sending.schedule.SendSlotPlanner;
import co.com.gtc.prospeccion.sending.service.SendGuardService;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/**
 * Reglas de envío (tarea 5.3). Cuentan para el límite diario y el espaciado los correos en cola y los ya enviados;
 * los fallidos y cancelados no.
 */
@Service
public class SendGuardServiceImpl implements SendGuardService {

    static final List<String> OCCUPYING = List.of(SendQueueStatus.QUEUED, SendQueueStatus.SENT);
    static final List<String> SENT = List.of(SendQueueStatus.SENT);
    /** Tope de la vista previa. */
    static final int MAX_PREVIEW = 200;

    private final Ley2300Calendar calendar;
    private final SendQueueRepository queueRepository;
    private final TenantConfigRepository configRepository;
    private final SuppressionService suppressionService;

    public SendGuardServiceImpl(Ley2300Calendar calendar, SendQueueRepository queueRepository,
            TenantConfigRepository configRepository, SuppressionService suppressionService) {
        this.calendar = calendar;
        this.queueRepository = queueRepository;
        this.configRepository = configRepository;
        this.suppressionService = suppressionService;
    }

    @Override
    public boolean isWithinLey2300Window(ZonedDateTime now) {
        return calendar.isAllowed(now);
    }

    @Override
    public boolean isHoliday(LocalDate date) {
        return calendar.isHoliday(date);
    }

    @Override
    public SendSlotPlanner.Limits currentLimits() {
        TenantConfigET config = configRepository.findFirstByFlgActiveTrueOrderByIdDesc().orElse(null);
        Integer daily = config == null ? null : config.getDailySendLimit();
        Integer spacing = config == null ? null : config.getMinSpacingSeconds();
        return new SendSlotPlanner.Limits(
                daily == null ? DEFAULT_DAILY_LIMIT : daily,
                spacing == null ? DEFAULT_SPACING_SECONDS : spacing);
    }

    /** Al despachar solo cuentan los ya enviados hoy: los que están en cola son justamente los que van a salir. */
    @Override
    public boolean hasDailyQuota(ZonedDateTime now) {
        int limit = currentLimits().dailyLimit();
        return limit > 0 && countOn(now.withZoneSameInstant(calendar.zone()).toLocalDate(), SENT) < limit;
    }

    @Override
    public boolean isSuppressed(String email) {
        return suppressionService.isSuppressed(email);
    }

    @Override
    public LocalDateTime nextAllowedSlotUtc(ZonedDateTime now) {
        ZonedDateTime slot = SendSlotPlanner.next(calendar, now, lastScheduled(), currentLimits(), this::scheduledOn);
        return toUtc(slot);
    }

    @Override
    public List<ZonedDateTime> previewSlots(ZonedDateTime from, int count) {
        int n = Math.max(1, Math.min(count, MAX_PREVIEW));
        return SendSlotPlanner.plan(calendar, from, lastScheduled(), currentLimits(), this::scheduledOn, n);
    }

    @Override
    public int scheduledOn(LocalDate date) {
        return countOn(date, OCCUPYING);
    }

    private int countOn(LocalDate date, List<String> codes) {
        LocalDateTime from = toUtc(date.atStartOfDay(calendar.zone()));
        LocalDateTime to = toUtc(date.plusDays(1).atStartOfDay(calendar.zone()));
        return (int) queueRepository.countScheduledBetween(from, to, SendQueueStatus.TABLECODE, codes);
    }

    private ZonedDateTime lastScheduled() {
        LocalDateTime last = queueRepository.findLastScheduled(SendQueueStatus.TABLECODE, OCCUPYING);
        return last == null ? null : last.atZone(ZoneOffset.UTC).withZoneSameInstant(calendar.zone());
    }

    static LocalDateTime toUtc(ZonedDateTime instant) {
        return instant.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
