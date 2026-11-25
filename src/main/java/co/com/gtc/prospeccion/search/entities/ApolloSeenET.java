package co.com.gtc.prospeccion.search.entities;

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
 * Memoria de Apollo: personas ya procesadas, para no pagar dos veces por la misma (única por PROVIDER_ID).
 */
@Entity
@Table(name = "la_apolloseen")
@Getter
@Setter
@NoArgsConstructor
public class ApolloSeenET extends AuditableET {

    public static final String RESULT_CREATED = "CREADO";
    public static final String RESULT_DISCARDED_AI = "DESCARTADO_IA";
    public static final String RESULT_NO_EMAIL = "SIN_CORREO";
    public static final String RESULT_EXCLUDED = "EXCLUIDO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    /** ID de la persona en Apollo. */
    @Column(name = "PROVIDER_ID", nullable = false, length = 100)
    private String providerId;

    /** CREADO, DESCARTADO_IA, SIN_CORREO o EXCLUIDO. */
    @Column(name = "RESULT", nullable = false, length = 20)
    private String result;

    @Column(name = "SCORE")
    private Integer score;

    /** la_searchrun.ID que la trajo. */
    @Column(name = "SEARCHRUNID", nullable = false)
    private Integer searchRunId;

    /** pm_leafletcontact.ID, null si no se creó en el CRM. */
    @Column(name = "PM_LEAFLETCONTACTID")
    private Integer pmLeafletContactId;
}
