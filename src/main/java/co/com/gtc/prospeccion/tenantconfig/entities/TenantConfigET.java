package co.com.gtc.prospeccion.tenantconfig.entities;

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
 * Configuración del robot en la base del tenant. Se versiona: cada cambio crea una fila nueva y solo una
 * tiene FLGACTIVE = 1 (la vigente). Cada búsqueda guarda la configuración que usó (la_searchrun.TENANTCONFIGID).
 */
@Entity
@Table(name = "la_tenantconfig")
@Getter
@Setter
@NoArgsConstructor
public class TenantConfigET extends AuditableET {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    /** JSON con el formato de docs/negocio/perfil-de-interes.md. */
    @Column(name = "PROFILE_OF_INTEREST", columnDefinition = "TEXT")
    private String profileOfInterest;

    /** Texto con el formato de docs/negocio/mensaje-base.md. */
    @Column(name = "BASE_MESSAGE", columnDefinition = "TEXT")
    private String baseMessage;

    @Column(name = "SCORE_THRESHOLD")
    private Integer scoreThreshold;

    @Column(name = "DAILY_SEND_LIMIT")
    private Integer dailySendLimit;

    @Column(name = "MIN_SPACING_SECONDS")
    private Integer minSpacingSeconds;

    /** Nombre del parámetro SSM con las credenciales del buzón (nunca la credencial). */
    @Column(name = "MAILBOX_SSM_KEY")
    private String mailboxSsmKey;

    /** No está en el diagrama v3.5: lo usa la plantilla del correo (enlace a LinkedIn de la empresa). */
    @Column(name = "COMPANY_LINKEDIN_URL")
    private String companyLinkedinUrl;

    /** Versión vigente. */
    @Column(name = "FLGACTIVE", nullable = false)
    private Boolean flgActive;
}
