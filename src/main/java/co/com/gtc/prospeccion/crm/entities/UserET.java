package co.com.gtc.prospeccion.crm.entities;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * sc_user del CRM. Solo lectura: Leads no es dueño de esta tabla.
 * Solo las columnas que usa el módulo (tomadas de UserET de msa-cotizaciones).
 */
@Entity
@Immutable
@Table(name = "sc_user")
@Getter
@NoArgsConstructor
public class UserET {

    @Id
    @Column(name = "ID")
    private Integer id;

    /** Login: es el userName que piden los endpoints de msa-cotizaciones sin variante por ID. */
    @Column(name = "LOGIN")
    private String login;

    @Column(name = "NAME")
    private String name;

    @Column(name = "LASTNAME")
    private String lastName;

    /** Dato personal: no registrar en logs. */
    @Column(name = "EMAIL")
    private String email;
}
