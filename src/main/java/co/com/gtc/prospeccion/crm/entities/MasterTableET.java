package co.com.gtc.prospeccion.crm.entities;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * sy_mastertable del CRM (estados y tipos). Solo lectura.
 * TABLECODE es numérico y CODE es texto. Columnas tomadas de MasterTableET de msa-cotizaciones.
 */
@Entity
@Immutable
@Table(name = "sy_mastertable")
@Getter
@NoArgsConstructor
public class MasterTableET {

    @Id
    @Column(name = "ID")
    private Integer id;

    @Column(name = "TABLECODE")
    private Integer tableCode;

    @Column(name = "CODE")
    private String code;

    @Column(name = "VALUE")
    private String value;

    /** Idioma del texto de VALUE (el nombre de la columna conserva la ortografía del CRM). */
    @Column(name = "LANGUAJEID")
    private Integer languajeId;

    @Column(name = "STATUS")
    private String status;
}
