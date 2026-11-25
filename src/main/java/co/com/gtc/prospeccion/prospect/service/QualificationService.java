package co.com.gtc.prospeccion.prospect.service;

import java.util.List;

import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.prospect.dto.request.QualificationPreviewRequest;

/**
 * Califica de 0 a 100 qué tanto encaja cada persona con el perfil de interés del tenant (tarea 3.5).
 * Se usa después de la búsqueda (gratis) y antes de conseguir el correo (gasta créditos): solo los que alcanzan el
 * umbral del tenant ({@code qualified = true}) pasan a 4.1.
 */
public interface QualificationService {

    /**
     * @param people  personas devueltas por People API Search
     * @param filters filtros con los que se buscaron: People API Search no trae sector, tamaño ni ubicación, así que
     *                los filtros son la evidencia de esos criterios
     * @return un resultado por persona, en el mismo orden
     */
    List<GeminiQualificationResult> qualify(List<ApolloSearchPersonDTO> people, ApolloPeopleSearchRequest filters);

    /**
     * Califica contactos escritos a mano, con el perfil y el umbral de la petición o, si no vienen, con los de la
     * configuración vigente. No guarda nada: sirve para ajustar el perfil antes de guardarlo.
     */
    List<GeminiQualificationResult> preview(QualificationPreviewRequest request);
}
