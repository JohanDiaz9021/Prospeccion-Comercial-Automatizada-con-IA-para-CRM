package co.com.gtc.prospeccion.common.entities;

import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * Auditoría estándar de las tablas la_* (modelo de datos v3.5):
 * CREATEDDATE / LASTUPDATEDDATE -> sy_datetable.ID y CREATEDBY / LASTUPDATEDBY -> sc_user.ID.
 * La llenan los servicios con DateTableResolver y CurrentUserProvider (o el usuario del robot en los jobs).
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AuditableET {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CREATEDDATE", nullable = false)
    private DateTableET createdDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CREATEDBY", nullable = false)
    private UserET createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LASTUPDATEDDATE")
    private DateTableET lastUpdatedDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LASTUPDATEDBY")
    private UserET lastUpdatedBy;
}
