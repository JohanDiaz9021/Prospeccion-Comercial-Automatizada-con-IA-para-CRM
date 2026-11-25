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
 * De dónde salió cada dato del contacto (trazabilidad; Gemini solo usa lo que está aquí).
 * Única por (PM_LEAFLETCONTACTID, SOURCE, FIELD).
 */
@Entity
@Table(name = "la_evidence")
@Getter
@Setter
@NoArgsConstructor
public class EvidenceET extends AuditableET {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    @Column(name = "PM_LEAFLETCONTACTID", nullable = false)
    private Integer pmLeafletContactId;

    /** APOLLO, HUNTER... */
    @Column(name = "SOURCE", nullable = false, length = 30)
    private String source;

    @Column(name = "FIELD", nullable = false, length = 50)
    private String field;

    @Column(name = "VALUE", length = 1000)
    private String value;

    @Column(name = "SOURCE_URL", length = 500)
    private String sourceUrl;
}
