package co.com.gtc.prospeccion.search.dto.response;

/**
 * Qué pasó con una persona nueva en una búsqueda (la_apolloseen). De las descartadas no se guarda nombre ni empresa:
 * solo el resultado y el puntaje.
 *
 * @param result           CREADO, DESCARTADO_IA, SIN_CORREO o EXCLUIDO
 * @param pmLeafletContactId contacto creado (solo CREADO)
 */
public record SearchResultResponseDTO(String result, Integer score, Integer pmLeafletContactId, String firstName,
        String title, String organizationName) {
}
