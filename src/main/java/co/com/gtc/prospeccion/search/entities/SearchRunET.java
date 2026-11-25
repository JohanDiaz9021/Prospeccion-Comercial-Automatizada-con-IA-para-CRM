package co.com.gtc.prospeccion.search.entities;

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
 * Una ejecución de búsqueda: frase del usuario, filtros generados, configuración usada y conteos del embudo.
 */
@Entity
@Table(name = "la_searchrun")
@Getter
@Setter
@NoArgsConstructor
public class SearchRunET extends AuditableET {

    /** Campaña que se repite cada día hábil. */
    public static final String DAILY = "DAILY";
    /** Campaña que se repite una vez por semana (7 días después de su última ejecución). */
    public static final String WEEKLY = "WEEKLY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    /** la_tenantconfig.ID: configuración (perfil, umbral) con que se corrió. */
    @Column(name = "TENANTCONFIGID", nullable = false)
    private Integer tenantConfigId;

    /** pm_list_contact.ID (lista del CRM donde quedan los contactos), opcional. */
    @Column(name = "PM_LISTCONTACTID")
    private Integer pmListContactId;

    @Column(name = "NATURAL_QUERY", length = 1000)
    private String naturalQuery;

    /** Filtros de Apollo en JSON (columna json de MySQL). */
    @Column(name = "FILTERS_JSON", columnDefinition = "json", nullable = false)
    private String filtersJson;

    @Column(name = "FOUND_COUNT", nullable = false)
    private Integer foundCount;

    @Column(name = "NEW_COUNT", nullable = false)
    private Integer newCount;

    @Column(name = "QUALIFIED_COUNT", nullable = false)
    private Integer qualifiedCount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "MT_STATUSID", nullable = false)
    private MasterTableET status;

    @Column(name = "ERROR_DETAIL", columnDefinition = "TEXT")
    private String errorDetail;

    /** Campaña: ScheduledSearchJob la repite cada día hábil con los mismos filtros. */
    @Column(name = "FLG_SCHEDULED", nullable = false)
    private Boolean flgScheduled = false;

    /** Cada cuánto se repite la campaña: {@link #DAILY} o {@link #WEEKLY}. */
    @Column(name = "SCHEDULE_FREQUENCY", length = 10)
    private String scheduleFrequency;

    /** la_searchrun.ID de la campaña que originó esta ejecución automática (null si la lanzó una persona). */
    @Column(name = "PARENT_SEARCHRUNID")
    private Integer parentSearchRunId;
}
