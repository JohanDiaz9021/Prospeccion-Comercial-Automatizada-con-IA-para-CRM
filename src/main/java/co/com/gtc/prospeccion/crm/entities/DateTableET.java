package co.com.gtc.prospeccion.crm.entities;

import java.time.LocalDate;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * sy_datetable del CRM (calendario: una fila por día). Las columnas de auditoría (CREATEDDATE, APPROVEDDATE)
 * guardan su ID. Solo lectura. Columnas tomadas de DateTableET de msa-cotizaciones.
 */
@Entity
@Immutable
@Table(name = "sy_datetable")
@Getter
@NoArgsConstructor
public class DateTableET {

    @Id
    @Column(name = "ID")
    private Integer id;

    @Column(name = "DATE")
    private LocalDate date;

    /** Fecha como texto de 8 caracteres. El CRM la consulta en dos formatos (yyyyMMdd y ddMMyyyy): por confirmar. */
    @Column(name = "DATECHAR")
    private String dateChar;
}
