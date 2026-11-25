package co.com.gtc.prospeccion.credits.entities;

import java.math.BigDecimal;

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
 * Libro de créditos consumidos en Apollo, Hunter y Gemini.
 */
@Entity
@Table(name = "la_creditledger")
@Getter
@Setter
@NoArgsConstructor
public class CreditLedgerET extends AuditableET {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Integer id;

    /** APOLLO, HUNTER o GEMINI. */
    @Column(name = "PROVIDER", nullable = false, length = 20)
    private String provider;

    @Column(name = "OPERATION", nullable = false, length = 50)
    private String operation;

    @Column(name = "CREDITS", nullable = false, precision = 12, scale = 4)
    private BigDecimal credits;

    @Column(name = "COST_USD", precision = 12, scale = 6)
    private BigDecimal costUsd;

    /** la_searchrun.ID; null cuando el gasto no pertenece a una búsqueda (por ejemplo, interpretar la frase). */
    @Column(name = "SEARCHRUNID")
    private Integer searchRunId;

    /** pm_leafletcontact.ID, si el gasto es de un contacto. */
    @Column(name = "PM_LEAFLETCONTACTID")
    private Integer pmLeafletContactId;
}
