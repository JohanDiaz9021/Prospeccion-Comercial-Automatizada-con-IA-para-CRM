package co.com.gtc.prospeccion.search.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import co.com.gtc.prospeccion.search.dto.request.RunSearchRequestDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchPreviewResponseDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchResultResponseDTO;
import co.com.gtc.prospeccion.search.dto.response.SearchRunResponseDTO;

/**
 * Orquesta la búsqueda: Apollo, descartes, calificación, correo, creación del prospecto y borrador (tarea 3.2).
 */
public interface ProspectSearchService {

    /** Total de Apollo y una muestra, sin crear prospectos ni gastar créditos. */
    SearchPreviewResponseDTO preview(RunSearchRequestDTO request);

    /**
     * Guarda la búsqueda y la ejecuta en segundo plano. Responde de inmediato con la ejecución en estado Pendiente;
     * su avance se consulta con {@link #getRun}. Con {@code scheduled = true} queda además como campaña diaria.
     */
    SearchRunResponseDTO run(RunSearchRequestDTO request);

    /** La más reciente primero, con el embudo de cada una. */
    Page<SearchRunResponseDTO> listRuns(Pageable pageable);

    SearchRunResponseDTO getRun(Integer id);

    /** Qué pasó con cada persona nueva de la ejecución. */
    List<SearchResultResponseDTO> runResults(Integer id);

    /**
     * Activa o pausa la campaña (solo una búsqueda lanzada por una persona puede ser campaña).
     *
     * @param frequency DAILY o WEEKLY; null = la que tenía (o DAILY si nunca fue campaña)
     */
    SearchRunResponseDTO setScheduled(Integer id, boolean scheduled, String frequency);

    /**
     * Llamado por ScheduledSearchJob, ya dentro del tenant: ejecuta de nuevo cada campaña activa a la que le toca hoy,
     * con sus filtros y el perfil vigente. Diaria: si no corrió hoy. Semanal: si su última ejecución fue hace 7 días o
     * más. Devuelve cuántas ejecutó.
     *
     * @param force true = todas las activas, les toque o no (herramienta local "Repetir campañas ahora")
     */
    int rerunScheduledSearches(boolean force);
}
