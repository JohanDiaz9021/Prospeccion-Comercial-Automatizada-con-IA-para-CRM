package co.com.gtc.prospeccion.dashboard.persistence.impl;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SendQueueStatus;
import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO.LastRun;
import co.com.gtc.prospeccion.dashboard.persistence.DashboardDAO;

/**
 * SQL de solo lectura para el panel. Los contactos de una búsqueda se siguen por la_contactext.SEARCHRUNID; los
 * borradores cuentan por contacto (su última versión), no por versión.
 */
@Component
public class DashboardDAOImpl implements DashboardDAO {

    /** Borradores de los contactos que trajeron las búsquedas :ids. */
    private static final String DRAFTS_OF_RUNS = "FROM la_draft d JOIN la_contactext x "
            + "ON x.PM_LEAFLETCONTACTID = d.PM_LEAFLETCONTACTID AND x.SEARCHRUNID IN (:ids) ";
    /** Última versión del borrador de cada contacto. */
    private static final String LATEST_VERSION = "d.VERSION = (SELECT MAX(d2.VERSION) FROM la_draft d2 "
            + "WHERE d2.PM_LEAFLETCONTACTID = d.PM_LEAFLETCONTACTID) ";

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardDAOImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RunTotals> findRuns(LocalDate from, LocalDate to, Integer campaignId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)).addValue("run", campaignId);
        return jdbc.query("SELECT r.ID, r.NEW_COUNT, r.QUALIFIED_COUNT FROM la_searchrun r "
                + "JOIN sy_datetable dt ON dt.ID = r.CREATEDDATE WHERE dt.DATE BETWEEN :from AND :to "
                + "AND (:run IS NULL OR r.ID = :run OR r.PARENT_SEARCHRUNID = :run) ORDER BY r.ID", p,
                (rs, i) -> new RunTotals(rs.getInt(1), rs.getLong(2), rs.getLong(3)));
    }

    @Override
    public Map<String, Long> countSeenResults(Collection<Integer> runIds) {
        return grouped("SELECT s.RESULT, COUNT(*) FROM la_apolloseen s WHERE s.SEARCHRUNID IN (:ids) "
                + "GROUP BY s.RESULT", runIds);
    }

    @Override
    public Map<String, Long> countLatestDraftStatus(Collection<Integer> runIds) {
        return grouped("SELECT m.CODE, COUNT(*) " + DRAFTS_OF_RUNS
                + "JOIN sy_mastertable m ON m.ID = d.MT_STATUSID WHERE " + LATEST_VERSION + "GROUP BY m.CODE", runIds);
    }

    @Override
    public Map<String, Long> countSendStatus(Collection<Integer> runIds) {
        return grouped("SELECT m.CODE, COUNT(*) FROM la_sendqueue q JOIN la_draft d ON d.ID = q.DRAFTID "
                + "JOIN la_contactext x ON x.PM_LEAFLETCONTACTID = d.PM_LEAFLETCONTACTID AND x.SEARCHRUNID IN (:ids) "
                + "JOIN sy_mastertable m ON m.ID = q.MT_STATUSID GROUP BY m.CODE", runIds);
    }

    @Override
    public Map<String, Long> countEventTypes(Collection<Integer> runIds) {
        return grouped("SELECT m.CODE, COUNT(DISTINCT e.SENDQUEUEID) FROM la_emailevent e "
                + "JOIN la_sendqueue q ON q.ID = e.SENDQUEUEID JOIN la_draft d ON d.ID = q.DRAFTID "
                + "JOIN la_contactext x ON x.PM_LEAFLETCONTACTID = d.PM_LEAFLETCONTACTID AND x.SEARCHRUNID IN (:ids) "
                + "JOIN sy_mastertable m ON m.ID = e.MT_TYPEID GROUP BY m.CODE", runIds);
    }

    @Override
    public long countDraftsToReview() {
        return count("SELECT COUNT(*) FROM la_draft d JOIN sy_mastertable m ON m.ID = d.MT_STATUSID "
                + "WHERE m.TABLECODE = :table AND m.CODE = :code AND " + LATEST_VERSION,
                new MapSqlParameterSource("table", DraftStatus.TABLECODE).addValue("code", DraftStatus.PENDING_REVIEW));
    }

    @Override
    public long countQueued() {
        return count("SELECT COUNT(*) FROM la_sendqueue q JOIN sy_mastertable m ON m.ID = q.MT_STATUSID "
                + "WHERE m.TABLECODE = :table AND m.CODE = :code", queuedParams());
    }

    @Override
    public Optional<LocalDateTime> nextScheduledUtc() {
        Timestamp next = jdbc.queryForObject("SELECT MIN(q.SCHEDULED_AT) FROM la_sendqueue q "
                + "JOIN sy_mastertable m ON m.ID = q.MT_STATUSID WHERE m.TABLECODE = :table AND m.CODE = :code",
                queuedParams(), Timestamp.class);
        return Optional.ofNullable(next).map(Timestamp::toLocalDateTime);
    }

    @Override
    public long countActiveCampaigns() {
        return count("SELECT COUNT(*) FROM la_searchrun WHERE FLG_SCHEDULED = 1", new MapSqlParameterSource());
    }

    @Override
    public Optional<LastRun> lastRun() {
        List<LastRun> rows = jdbc.query("SELECT r.ID, r.NATURAL_QUERY, m.CODE, dt.DATE FROM la_searchrun r "
                + "JOIN sy_mastertable m ON m.ID = r.MT_STATUSID JOIN sy_datetable dt ON dt.ID = r.CREATEDDATE "
                + "ORDER BY r.ID DESC LIMIT 1", new MapSqlParameterSource(),
                (rs, i) -> new LastRun(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate()));
        return rows.stream().findFirst();
    }

    private MapSqlParameterSource queuedParams() {
        return new MapSqlParameterSource("table", SendQueueStatus.TABLECODE).addValue("code", SendQueueStatus.QUEUED);
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private Map<String, Long> grouped(String sql, Collection<Integer> runIds) {
        Map<String, Long> counts = new HashMap<>();
        if (runIds.isEmpty()) {
            return counts;
        }
        jdbc.query(sql, new MapSqlParameterSource("ids", runIds),
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        return counts;
    }
}
