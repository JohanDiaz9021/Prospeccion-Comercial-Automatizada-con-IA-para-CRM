package co.com.gtc.prospeccion.tracking.entities;

import java.time.LocalDateTime;

import co.com.gtc.prospeccion.common.entities.AuditableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Evento de un correo enviado: envío, respuesta, rebote o baja.
 * PROVIDER_ID (evento de Gmail o Graph) es único: un evento del buzón no se registra dos veces.
 */
@Entity
@Table(name = "la_emailevent")
@Getter
@Setter
@NoArgsConstructor
public class EmailEventET extends AuditableET {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    /** la_sendqueue.ID. */
    @Column(name = "SENDQUEUEID", nullable = false)
    private Integer sendQueueId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "MT_TYPEID", nullable = false)
    private MasterTableET type;

    /** UTC. */
    @Column(name = "OCCURRED_AT", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "PROVIDER_ID")
    private String providerId;
}
