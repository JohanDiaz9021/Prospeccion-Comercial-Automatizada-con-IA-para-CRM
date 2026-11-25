package co.com.gtc.prospeccion.integration.apollo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persona como la devuelve People API Search. No trae correo, teléfono, apellido completo ni datos completos de la
 * empresa: solo banderas has_*. Para eso está People Enrichment (ApolloPersonDTO, tarea 4.1, gasta créditos).
 * No registrar en logs: contiene datos personales.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ApolloSearchPersonDTO {

    /** ID de la persona en Apollo (PROVIDER_ID en la_apolloseen y la_contactext). */
    private String id;
    private String firstName;
    /** Apellido ocultado por Apollo, por ejemplo "Hu***n". */
    private String lastNameObfuscated;
    private String title;
    private String lastRefreshedAt;
    /** Apollo tiene un correo para esta persona (se obtiene con enrichment, 1 crédito). */
    private Boolean hasEmail;
    private Boolean hasCity;
    private Boolean hasState;
    private Boolean hasCountry;
    /** "Yes" o "No" (Apollo lo devuelve como texto). */
    private String hasDirectPhone;
    private Organization organization;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Organization {
        private String name;
        private Boolean hasIndustry;
        private Boolean hasPhone;
        private Boolean hasCity;
        private Boolean hasState;
        private Boolean hasCountry;
        private Boolean hasZipCode;
        private Boolean hasRevenue;
        private Boolean hasEmployeeCount;
    }
}
