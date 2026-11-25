package co.com.gtc.prospeccion.integration.apollo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Empresa según Apollo. TODO: confirmar campos contra la respuesta real.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ApolloOrganizationDTO {

    private String id;
    private String name;
    private String primaryDomain;
    private String industry;
    private Integer estimatedNumEmployees;
    private String city;
    private String linkedinUrl;
}
