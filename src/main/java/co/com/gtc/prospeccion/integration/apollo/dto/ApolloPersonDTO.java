package co.com.gtc.prospeccion.integration.apollo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persona según Apollo. TODO: confirmar campos contra la respuesta real.
 * No registrar en logs: contiene datos personales.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ApolloPersonDTO {

    private String id;
    private String firstName;
    private String lastName;
    private String title;
    private String seniority;
    private String linkedinUrl;
    private String email;
    private String emailStatus;
    private String city;
    private ApolloOrganizationDTO organization;
}
