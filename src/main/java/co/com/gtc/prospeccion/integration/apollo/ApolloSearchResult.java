package co.com.gtc.prospeccion.integration.apollo;

import java.util.List;

import co.com.gtc.prospeccion.integration.apollo.ApolloSearchPage.ApolloRateLimit;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;

/**
 * Resultado de recorrer varias páginas de People API Search.
 *
 * @param totalEntries total que reporta Apollo para los filtros
 * @param people       personas traídas (como máximo maxResults y 50.000)
 * @param pagesFetched páginas pedidas (cada una cuenta para los límites de tasa, no para créditos)
 * @param rateLimit    cupo restante después de la última página
 */
public record ApolloSearchResult(int totalEntries, List<ApolloSearchPersonDTO> people, int pagesFetched,
        ApolloRateLimit rateLimit) {
}
