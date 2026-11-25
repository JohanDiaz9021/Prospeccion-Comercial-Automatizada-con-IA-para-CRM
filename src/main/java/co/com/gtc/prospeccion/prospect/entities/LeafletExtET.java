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
 * Datos de la empresa que trae Apollo y el CRM no guarda. Uno a uno con pm_leaflet (msa-cotizaciones).
 */
@Entity
@Table(name = "la_leafletext")
@Getter
@Setter
@NoArgsConstructor
public class LeafletExtET extends AuditableET {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    @Column(name = "PM_LEAFLETID", nullable = false)
    private Integer pmLeafletId;

    /** ID de la organización en Apollo. */
    @Column(name = "PROVIDER_ID", length = 100)
    private String providerId;

    @Column(name = "COMPANY_DOMAIN")
    private String companyDomain;

    @Column(name = "INDUSTRY", length = 150)
    private String industry;

    @Column(name = "COMPANY_SIZE", length = 30)
    private String companySize;

    @Column(name = "CITY", length = 150)
    private String city;

    @Column(name = "COMPANY_LINKEDIN_URL", length = 500)
    private String companyLinkedinUrl;
}
