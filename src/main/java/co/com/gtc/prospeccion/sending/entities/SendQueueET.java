package co.com.gtc.prospeccion.sending.entities;

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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cola de envío: una fila por borrador aprobado (única por DRAFTID). VERSION es bloqueo optimista; las filas
 * pendientes se toman con FOR UPDATE SKIP LOCKED para que dos instancias no envíen el mismo correo.
 */
@Entity
@Table(name = "la_sendqueue")
@Getter
@Setter
@NoArgsConstructor
public class SendQueueET extends AuditableET {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    /** la_draft.ID aprobado. */
    @Column(name = "DRAFTID", nullable = false)
    private Integer draftId;

    /** Destinatario en minúsculas, tomado de la evidencia del contacto al encolar (no está en el diagrama v3.5). */
    @Column(name = "RECIPIENT_EMAIL", length = 320)
    private String recipientEmail;

    /** UTC. */
    @Column(name = "SCHEDULED_AT", nullable = false)
    private LocalDateTime scheduledAt;

    /** UTC. */
    @Column(name = "SENT_AT")
    private LocalDateTime sentAt;

    @Column(name = "ATTEMPTS", nullable = false)
    private Integer attempts;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "MT_STATUSID", nullable = false)
    private MasterTableET status;

    /** ID del mensaje en el proveedor del buzón (Gmail o Graph). */
    @Column(name = "PROVIDER_ID")
    private String providerId;

    @Column(name = "THREAD_ID")
    private String threadId;
}
