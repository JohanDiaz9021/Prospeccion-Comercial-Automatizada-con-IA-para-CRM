package co.com.gtc.prospeccion.sending.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.sending.entities.SendQueueET;

@Repository
public interface SendQueueRepository extends JpaRepository<SendQueueET, Integer> {

    /** Con el estado cargado (open-in-view = false). */
    @EntityGraph(attributePaths = "status")
    Optional<SendQueueET> findByDraftId(Integer draftId);

    /** Envíos de un estado al mismo destinatario (la baja cancela los que siguen en cola). */
    List<SendQueueET> findByRecipientEmailAndStatus_TableCodeAndStatus_Code(String recipientEmail, Integer tableCode,
            String code);

    /** Correos programados o enviados entre dos instantes UTC (para el límite diario). */
    @Query("""
            select count(q) from SendQueueET q
            where q.scheduledAt >= :fromUtc and q.scheduledAt < :toUtc
              and q.status.tableCode = :tableCode and q.status.code in :codes""")
    long countScheduledBetween(@Param("fromUtc") LocalDateTime fromUtc, @Param("toUtc") LocalDateTime toUtc,
            @Param("tableCode") Integer tableCode, @Param("codes") Collection<String> codes);

    /** Último correo programado o enviado (para el espaciado). */
    @Query("""
            select max(q.scheduledAt) from SendQueueET q
            where q.status.tableCode = :tableCode and q.status.code in :codes""")
    LocalDateTime findLastScheduled(@Param("tableCode") Integer tableCode, @Param("codes") Collection<String> codes);

    /** Correos pendientes cuya hora ya llegó (UTC). */
    @Query("""
            select count(q) from SendQueueET q
            where q.scheduledAt <= :nowUtc and q.status.tableCode = :tableCode and q.status.code = :code""")
    long countDue(@Param("nowUtc") LocalDateTime nowUtc, @Param("tableCode") Integer tableCode,
            @Param("code") String code);

    /** Resumen de la cola: [código de estado, cantidad]. */
    @Query("""
            select q.status.code, count(q) from SendQueueET q
            where q.status.tableCode = :tableCode group by q.status.code""")
    List<Object[]> countByStatus(@Param("tableCode") Integer tableCode);

    /** Próximo correo pendiente. */
    Optional<SendQueueET> findFirstByStatus_TableCodeAndStatus_CodeOrderByScheduledAtAsc(Integer tableCode, String code);

    /**
     * Tarea 5.2: toma el próximo correo con turno y lo bloquea hasta el fin de la transacción. SKIP LOCKED hace que dos
     * instancias del servicio nunca tomen el mismo (cada una salta lo que la otra ya bloqueó). "OF q" bloquea solo la
     * fila de la cola, no la del catálogo de estados.
     */
    @Query(value = """
            SELECT q.ID FROM la_sendqueue q JOIN sy_mastertable s ON s.ID = q.MT_STATUSID
            WHERE s.TABLECODE = :tableCode AND s.CODE = :code AND q.SCHEDULED_AT <= :dueUtc
            ORDER BY q.SCHEDULED_AT, q.ID
            LIMIT 1
            FOR UPDATE OF q SKIP LOCKED""", nativeQuery = true)
    Optional<Integer> claimNextDue(@Param("dueUtc") LocalDateTime dueUtc, @Param("tableCode") Integer tableCode,
            @Param("code") String code);

    /** Tarea 5.2: cuándo salió el último correo (UTC), para el espaciado entre envíos. */
    @Query("select max(q.sentAt) from SendQueueET q")
    LocalDateTime findLastSentAt();

    /** Tarea 5.4: el envío más reciente del hilo (threadId de Gmail o conversationId de Graph). */
    Optional<SendQueueET> findFirstByThreadIdOrderByIdDesc(String threadId);

    /** Tarea 5.4: el envío más reciente a esa dirección en ese estado (para asociar una respuesta o un rebote). */
    Optional<SendQueueET> findFirstByRecipientEmailAndStatus_TableCodeAndStatus_CodeOrderByIdDesc(String recipientEmail,
            Integer tableCode, String code);
}
