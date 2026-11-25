package co.com.gtc.prospeccion.draft.entities;

import co.com.gtc.prospeccion.common.entities.AuditableET;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
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
 * Borrador de correo para un contacto. Cada versión es una fila: única por (PM_LEAFLETCONTACTID, VERSION).
 * Una persona lo revisa y aprueba antes de enviarlo.
 */
@Entity
@Table(name = "la_draft")
@Getter
@Setter
@NoArgsConstructor
public class DraftET extends AuditableET {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    @Column(name = "PM_LEAFLETCONTACTID", nullable = false)
    private Integer pmLeafletContactId;

    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @Column(name = "SUBJECT", nullable = false)
    private String subject;

    /** HTML sanitizado (HtmlSanitizerUtil). La versión en texto se genera al enviar. */
    @Column(name = "BODY", columnDefinition = "TEXT", nullable = false)
    private String body;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "MT_STATUSID", nullable = false)
    private MasterTableET status;

    @Column(name = "EDITED_BY_HUMAN", nullable = false)
    private Boolean editedByHuman;

    /** Modelo de Gemini que redactó la versión (no está en el diagrama v3.5; igual que la_contactext.MODEL). */
    @Column(name = "MODEL", length = 100)
    private String model;

    /** Versión de prompts/draft-email.txt con que se redactó. */
    @Column(name = "PROMPT_VERSION", length = 30)
    private String promptVersion;

    /** Por qué se descartó (5.1). Sirve para ajustar el mensaje base y el perfil (no está en el diagrama v3.5). */
    @Column(name = "DISCARD_REASON", length = 500)
    private String discardReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "APPROVEDBY")
    private UserET approvedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "APPROVEDDATE")
    private DateTableET approvedDate;
}
