package co.com.gtc.prospeccion.integration.apollo;

import java.util.List;

import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;

/**
 * Una página de People API Search.
 *
 * @param totalEntries total que reporta Apollo para los filtros (puede pasar de 50.000; solo se pueden ver 50.000)
 * @param page         página pedida (desde 1)
 * @param perPage      tamaño de página pedido (máximo 100)
 * @param people       personas de la página
 * @param rateLimit    cupo que le queda al equipo en este endpoint, según los encabezados de Apollo
 */
public record ApolloSearchPage(int totalEntries, int page, int perPage, List<ApolloSearchPersonDTO> people,
        ApolloRateLimit rateLimit) {

    /** Hay más páginas por pedir, sin pasar del tope de visualización de Apollo. */
    public boolean hasMore() {
        int visible = Math.min(totalEntries, ApolloClient.MAX_VISIBLE_RESULTS);
        return !people.isEmpty() && page < ApolloClient.MAX_PAGE && (long) page * perPage < visible;
    }

    /**
     * Cupo restante según los encabezados x-*-requests-left (null si Apollo no limita esa ventana en el plan).
     */
    public record ApolloRateLimit(Integer minuteLeft, Integer hourLeft, Integer dayLeft) {

        public static final ApolloRateLimit UNKNOWN = new ApolloRateLimit(null, null, null);
    }
}
