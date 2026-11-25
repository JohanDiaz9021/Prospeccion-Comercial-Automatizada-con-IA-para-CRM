package co.com.gtc.prospeccion.dashboard.service;

import java.time.LocalDate;

import co.com.gtc.prospeccion.dashboard.dto.DashboardResponseDTO;

public interface DashboardService {

    /**
     * Panel del periodo (tarea 6.1).
     *
     * @param from       primer día (hora de Colombia); null = primer día del mes actual
     * @param to         último día, inclusive; null = hoy
     * @param campaignId una búsqueda o campaña (con sus ejecuciones automáticas); null = todas
     */
    DashboardResponseDTO get(LocalDate from, LocalDate to, Integer campaignId);
}
