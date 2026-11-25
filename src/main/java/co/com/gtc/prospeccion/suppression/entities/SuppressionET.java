package co.com.gtc.prospeccion.suppression.entities;

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
 * Lista de supresión: correos a los que nunca se vuelve a escribir (bajas, rebotes duros, quejas).
 * EMAIL es único y se guarda en minúsculas.
 */
@Entity
@Table(name = "la_suppression")
@Getter
@Setter
@NoArgsConstructor
public class SuppressionET extends AuditableET {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    @Column(name = "EMAIL", nullable = false, length = 320)
    private String email;

    @Column(name = "DOMAIN", nullable = false)
    private String domain;

    /** Motivo (baja, rebote duro, queja) en sy_mastertable. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "MT_REASONID", nullable = false)
    private MasterTableET reason;

    /** la_sendqueue.ID que originó la baja, si aplica. */
    @Column(name = "SENDQUEUEID")
    private Integer sendQueueId;
}
