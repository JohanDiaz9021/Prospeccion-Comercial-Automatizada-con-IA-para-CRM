package co.com.gtc.prospeccion.search.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SearchRunStatus;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.integration.apollo.ApolloClient;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.search.entities.SearchRunET;
import co.com.gtc.prospeccion.search.mapper.SearchMapper;
import co.com.gtc.prospeccion.search.persistence.SearchRunDAO;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/** Tarea 3.4: a qué campañas les toca correr hoy. */
class CampaignScheduleTest {

    static final LocalDate HOY = LocalDate.of(2026, 11, 24);

    private final SearchRunDAO dao = mock(SearchRunDAO.class);
    private final SearchPipeline pipeline = mock(SearchPipeline.class);
    private final TenantConfigRepository configs = mock(TenantConfigRepository.class);
    private final MasterTableRepository masters = mock(MasterTableRepository.class);
    private final DateTableResolver dates = mock(DateTableResolver.class);
    private ProspectSearchServiceImpl service;

    @BeforeEach
    void preparar() {
        DateTableET hoy = new DateTableET();
        ReflectionTestUtils.setField(hoy, "date", HOY);
        when(dates.today()).thenReturn(hoy);
        TenantConfigET config = new TenantConfigET();
        config.setId(3);
        when(configs.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(config));
        MasterTableET pendiente = new MasterTableET();
        ReflectionTestUtils.setField(pendiente, "code", SearchRunStatus.PENDING);
        when(masters.findFirstByTableCodeAndCode(any(), any())).thenReturn(Optional.of(pendiente));
        when(dao.save(any())).thenAnswer(i -> {
            SearchRunET r = i.getArgument(0);
            r.setId(100);
            return r;
        });
        service = new ProspectSearchServiceImpl(dao, pipeline, mock(SearchMapper.class), configs, masters,
                mock(EvidenceRepository.class), mock(ApolloClient.class), dates, mock(CurrentUserProvider.class),
                new ObjectMapper());
    }

    private SearchRunET campana(int id, String frecuencia, LocalDate ultima) {
        SearchRunET r = new SearchRunET();
        r.setId(id);
        r.setFlgScheduled(true);
        r.setScheduleFrequency(frecuencia);
        r.setFiltersJson("{}");
        UserET creador = new UserET();
        ReflectionTestUtils.setField(creador, "id", 5);
        r.setCreatedBy(creador);
        when(dao.lastExecutionDate(id)).thenReturn(Optional.ofNullable(ultima));
        return r;
    }

    @Test
    void diariaCorreSiNoHaCorridoHoy() {
        assertThat(service.isDue(campana(1, SearchRunET.DAILY, HOY.minusDays(1)), HOY)).isTrue();
        assertThat(service.isDue(campana(2, SearchRunET.DAILY, HOY), HOY)).isFalse();
        // Sin frecuencia guardada (campañas de antes de la 3.4) se comporta como diaria.
        assertThat(service.isDue(campana(3, null, HOY.minusDays(1)), HOY)).isTrue();
    }

    @Test
    void semanalCorreSieteDiasDespuesDeSuUltimaEjecucion() {
        assertThat(service.isDue(campana(1, SearchRunET.WEEKLY, HOY.minusDays(6)), HOY)).isFalse();
        assertThat(service.isDue(campana(2, SearchRunET.WEEKLY, HOY.minusDays(7)), HOY)).isTrue();
        assertThat(service.isDue(campana(3, SearchRunET.WEEKLY, HOY.minusDays(30)), HOY)).isTrue();
        assertThat(service.isDue(campana(4, SearchRunET.WEEKLY, null), HOY)).isTrue();
    }

    @Test
    void elJobSoloEjecutaLasQueTocanYAQuienCreoLaCampana() {
        List<SearchRunET> campanas = List.of(campana(1, SearchRunET.DAILY, HOY.minusDays(1)),
                campana(2, SearchRunET.WEEKLY, HOY.minusDays(3)), campana(3, SearchRunET.DAILY, HOY));
        when(dao.findScheduled()).thenReturn(campanas);

        int ejecutadas = service.rerunScheduledSearches(false);

        assertThat(ejecutadas).isEqualTo(1);
        verify(pipeline, times(1)).execute(100, 5);
    }

    @Test
    void forzarEjecutaTodasLasActivas() {
        List<SearchRunET> campanas = List.of(campana(1, SearchRunET.DAILY, HOY),
                campana(2, SearchRunET.WEEKLY, HOY.minusDays(1)));
        when(dao.findScheduled()).thenReturn(campanas);

        assertThat(service.rerunScheduledSearches(true)).isEqualTo(2);
        verify(pipeline, times(2)).execute(anyInt(), any());
    }

    @Test
    void sinConfiguracionActivaNoEjecutaNada() {
        List<SearchRunET> campanas = List.of(campana(1, SearchRunET.DAILY, null));
        when(dao.findScheduled()).thenReturn(campanas);
        when(configs.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());

        assertThat(service.rerunScheduledSearches(true)).isZero();
        verify(pipeline, never()).execute(anyInt(), any());
    }
}
