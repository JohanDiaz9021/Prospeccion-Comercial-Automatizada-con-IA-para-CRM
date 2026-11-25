package co.com.gtc.prospeccion.prospect.entities;

import co.com.gtc.prospeccion.common.entities.AuditableET;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Datos de IA del contacto (cargo, puntaje, origen del correo). Uno a uno con pm_leafletcontact.
 */
@Entity
@Table(name = "la_contactext")
@Getter
@Setter
@NoArgsConstructor
public class ContactExtET extends AuditableET {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    @Column(name = "PM_LEAFLETCONTACTID", nullable = false)
    private Integer pmLeafletContactId;

    /** ID de la persona en Apollo. */
    @Column(name = "PROVIDER_ID", nullable = false, length = 100)
    private String providerId;

    @Column(name = "TITLE")
    private String title;

    @Column(name = "SENIORITY", length = 50)
    private String seniority;

    @Column(name = "LINKEDIN_URL", length = 500)
    private String linkedinUrl;

    /** APOLLO o HUNTER. */
    @Column(name = "EMAIL_SOURCE", length = 20)
    private String emailSource;

    @Column(name = "EMAIL_VERIFICATION", length = 30)
    private String emailVerification;

    @Column(name = "SCORE")
    private Integer score;

    @Column(name = "SCORE_REASON", length = 500)
    private String scoreReason;

    @Column(name = "MODEL", length = 100)
    private String model;

    @Column(name = "PROMPT_VERSION", length = 30)
    private String promptVersion;

    /** la_searchrun.ID que lo trajo. */
    @Column(name = "SEARCHRUNID")
    private Integer searchRunId;
}
