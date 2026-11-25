package co.com.gtc.prospeccion.dashboard.service.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.EmailEventType;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO;
import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO.Funnel;
import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO.Outcomes;
import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO.Pending;
import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO.Rates;
import co.com.gtc.prospeccion.dashboard.persistence.DashboardDAO;
import co.com.gtc.prospeccion.dashboard.persistence.DashboardDAO.RunTotals;
import co.com.gtc.prospeccion.dashboard.service.DashboardService;
import co.com.gtc.prospeccion.search.entities.ApolloSeenET;

/**
 * Tarea 6.1: arma el panel con consultas agregadas. Ver {@link DashboardResponseDTO} para qué cuenta cada número.
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    /** Un año como máximo: el panel no es un reporte histórico. */
    static final int MAX_DAYS = 366;

    private final DashboardDAO dao;
    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public DashboardServiceImpl(DashboardDAO dao, ProspeccionProperties properties) {
        this(dao, ZoneId.of(properties.getTimezone()), Clock.systemUTC());
    }

    DashboardServiceImpl(DashboardDAO dao, ZoneId zone, Clock clock) {
        this.dao = dao;
        this.zone = zone;
        this.clock = clock;
    }

    @Override
    public DashboardResponseDTO get(LocalDate from, LocalDate to, Integer campaignId) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today;
        if (end.isBefore(start)) {
            throw new BusinessException(MessageKeys.VALIDATION_ERROR, "La fecha final es anterior a la inicial");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new BusinessException(MessageKeys.VALIDATION_ERROR, "El periodo no puede pasar de un año");
        }

        List<RunTotals> runs = dao.findRuns(start, end, campaignId);
        List<Integer> ids = runs.stream().map(RunTotals::id).toList();
        long processed = runs.stream().mapToLong(RunTotals::newCount).sum();
        long qualified = runs.stream().mapToLong(RunTotals::qualifiedCount).sum();
        Map<String, Long> seen = dao.countSeenResults(ids);
        Map<String, Long> drafts = dao.countLatestDraftStatus(ids);
        Map<String, Long> sends = dao.countSendStatus(ids);
        Map<String, Long> events = dao.countEventTypes(ids);

        long contacts = get(seen, ApolloSeenET.RESULT_CREATED);
        long drafted = drafts.values().stream().mapToLong(Long::longValue).sum();
        long approved = get(drafts, DraftStatus.APPROVED);
        long sent = get(sends, SendQueueStatus.SENT);
        long replied = get(events, EmailEventType.REPLY);
        long bounced = get(events, EmailEventType.BOUNCE);
        long unsubscribed = get(events, EmailEventType.UNSUBSCRIBE);

        Funnel funnel = new Funnel(processed, qualified, contacts, drafted, approved, sent, replied,
                get(events, EmailEventType.HANDOFF));
        Outcomes outcomes = new Outcomes(get(seen, ApolloSeenET.RESULT_DISCARDED_AI),
                get(seen, ApolloSeenET.RESULT_NO_EMAIL), get(seen, ApolloSeenET.RESULT_EXCLUDED),
                get(drafts, DraftStatus.PENDING_REVIEW), get(drafts, DraftStatus.DISCARDED),
                get(drafts, DraftStatus.EXPIRED), get(sends, SendQueueStatus.QUEUED), get(sends, SendQueueStatus.FAILED),
                get(sends, SendQueueStatus.CANCELLED), bounced, unsubscribed, get(events, EmailEventType.OPEN));
        Rates rates = new Rates(percent(qualified, processed), percent(approved, drafted), percent(replied, sent),
                percent(bounced, sent), percent(unsubscribed, sent));
        Pending pending = new Pending(dao.countDraftsToReview(), dao.countQueued(),
                dao.nextScheduledUtc().map(utc -> utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone)
                        .toLocalDateTime()).orElse(null),
                dao.countActiveCampaigns(), dao.lastRun().orElse(null));
        return new DashboardResponseDTO(start, end, campaignId, runs.size(), funnel, outcomes, rates, pending);
    }

    /** Porcentaje con un decimal, o null si la base es 0. */
    static Double percent(long part, long whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static long get(Map<String, Long> counts, String key) {
        return counts.getOrDefault(key, 0L);
    }
}
