package co.com.gtc.prospeccion.tracking.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.tracking.entities.EmailEventET;

@Repository
public interface EmailEventRepository extends JpaRepository<EmailEventET, Integer> {

    /** PROVIDER_ID es único: un evento no se registra dos veces. */
    boolean existsByProviderId(String providerId);

    /** Tarea 5.4: eventos de un tipo para todos los envíos a una dirección (por ejemplo, rebotes temporales). */
    @Query("select count(e) from EmailEventET e, SendQueueET q where q.id = e.sendQueueId "
            + "and q.recipientEmail = :email and e.type.tableCode = :tableCode and e.type.code = :code")
    long countByRecipientAndType(@Param("email") String email, @Param("tableCode") Integer tableCode,
            @Param("code") String code);

    /**
     * Tarea 5.6: envíos con una respuesta que todavía no pasaron al vendedor (sin evento "Pasó al vendedor"), el más
     * antiguo primero. Es la cola de pendientes: si el CRM no respondió, se reintenta en la siguiente pasada.
     */
    @Query("""
            select e.sendQueueId from EmailEventET e
            where e.type.tableCode = :tableCode and e.type.code = :replyCode
              and not exists (select h.id from EmailEventET h where h.sendQueueId = e.sendQueueId
                              and h.type.tableCode = :tableCode and h.type.code = :handoffCode)
            group by e.sendQueueId
            order by min(e.occurredAt)""")
    List<Integer> findRepliedWithoutHandoff(@Param("tableCode") Integer tableCode, @Param("replyCode") String replyCode,
            @Param("handoffCode") String handoffCode, Pageable pageable);

    /** La primera respuesta de un envío (su fecha va en la tarea del vendedor). */
    Optional<EmailEventET> findFirstBySendQueueIdAndType_TableCodeAndType_CodeOrderByOccurredAtAsc(Integer sendQueueId,
            Integer tableCode, String code);
}
