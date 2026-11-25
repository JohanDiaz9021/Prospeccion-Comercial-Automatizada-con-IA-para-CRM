package co.com.gtc.prospeccion.draft.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.exception.ConflictException;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.draft.dto.request.DiscardDraftRequestDTO;
import co.com.gtc.prospeccion.draft.dto.request.UpdateDraftRequestDTO;
import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.mapper.DraftMapperImpl;
import co.com.gtc.prospeccion.draft.persistence.DraftDAO;
import co.com.gtc.prospeccion.integration.gemini.GeminiClient;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

/** Bandeja de borradores (5.1): listar, editar como versión nueva y descartar. */
class DraftInboxTest {

    private final DraftDAO draftDAO = mock(DraftDAO.class);
    private final DraftSupport support = mock(DraftSupport.class);
    private final DateTableResolver dateTable = mock(DateTableResolver.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final DateTableET hoy = new DateTableET();
    private final UserET revisora = new UserET();
    private DraftServiceImpl service;

    static MasterTableET estado(String code) {
        MasterTableET m = new MasterTableET();
        ReflectionTestUtils.setField(m, "tableCode", DraftStatus.TABLECODE);
        ReflectionTestUtils.setField(m, "code", code);
        return m;
    }

    static DraftET borrador(int id, int version) {
        DraftET d = new DraftET();
        d.setId(id);
        d.setPmLeafletContactId(900);
        d.setVersion(version);
        d.setSubject("Clínica Demo: sistemas conectados");
        d.setBody("<p>Buenos días, Laura:</p><p>Equipo Comercial GTC</p>");
        d.setStatus(estado(DraftStatus.PENDING_REVIEW));
        d.setEditedByHuman(false);
        d.setModel("gemini-2.5-flash");
        d.setPromptVersion("draft-v1");
        return d;
    }

    static UpdateDraftRequestDTO edicion(String subject, String body, int version) {
        UpdateDraftRequestDTO r = new UpdateDraftRequestDTO();
        r.setSubject(subject);
        r.setBody(body);
        r.setVersion(version);
        return r;
    }

    @BeforeEach
    void preparar() {
        when(dateTable.today()).thenReturn(hoy);
        when(currentUser.getCurrentUser()).thenReturn(revisora);
        when(draftDAO.save(any())).thenAnswer(i -> i.getArgument(0));
        when(support.describe(any(DraftET.class))).thenAnswer(i -> {
            DraftResponseDTO dto = new DraftResponseDTO();
            dto.setVersion(((DraftET) i.getArgument(0)).getVersion());
            return dto;
        });
        service = new DraftServiceImpl(draftDAO, new DraftMapperImpl(), mock(TenantConfigRepository.class),
                mock(EvidenceRepository.class), mock(MasterTableRepository.class), dateTable, currentUser,
                mock(UserRepository.class), mock(GeminiClient.class), new ObjectMapper(), support,
                mock(SuppressionService.class));
    }

    @Test
    void laBandejaPaginaYCompletaLosDatos() {
        DraftET d = borrador(5, 2);
        PageRequest pagina = PageRequest.of(0, 20);
        when(draftDAO.findByStatus("1", pagina)).thenReturn(new PageImpl<>(List.of(d), pagina, 41));
        when(support.describe(List.of(d))).thenReturn(List.of(new DraftResponseDTO()));

        assertThat(service.list("1", pagina).getTotalElements()).isEqualTo(41);
        verify(support).describe(List.of(d));
    }

    @Test
    void editarCreaLaVersionSiguienteConElHtmlLimpio() {
        DraftET actual = borrador(5, 2);
        when(support.find(5)).thenReturn(actual);

        service.update(5, edicion("RE: Asunto nuevo", "<p>Hola, Laura:</p><script>x()</script><p>Equipo Comercial GTC</p>", 2));

        ArgumentCaptor<DraftET> guardado = ArgumentCaptor.forClass(DraftET.class);
        verify(draftDAO).save(guardado.capture());
        DraftET nuevo = guardado.getValue();
        assertThat(nuevo.getId()).isNull(); // fila nueva: la versión 2 queda como historial
        assertThat(nuevo.getVersion()).isEqualTo(3);
        assertThat(nuevo.getSubject()).isEqualTo("Asunto nuevo");
        assertThat(nuevo.getBody()).doesNotContain("script");
        assertThat(nuevo.getEditedByHuman()).isTrue();
        assertThat(nuevo.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(nuevo.getCreatedBy()).isSameAs(revisora);
        assertThat(nuevo.getStatus().getCode()).isEqualTo(DraftStatus.PENDING_REVIEW);
    }

    @Test
    void sinCambiosNoCreaOtraVersion() {
        DraftET actual = borrador(5, 2);
        when(support.find(5)).thenReturn(actual);

        service.update(5, edicion(actual.getSubject(), actual.getBody(), 2));

        verify(draftDAO, never()).save(any());
    }

    @Test
    void editarUnaVersionVistaViejaEsConflicto() {
        when(support.find(5)).thenReturn(borrador(5, 3));

        assertThatThrownBy(() -> service.update(5, edicion("Asunto", "<p>Texto</p>", 2)))
                .isInstanceOf(ConflictException.class);
        verify(draftDAO, never()).save(any());
    }

    @Test
    void marcasSinLlenarNoSeGuardan() {
        when(support.find(5)).thenReturn(borrador(5, 2));

        assertThatThrownBy(() -> service.update(5, edicion("Asunto", "<p>Hola, {{nombre}}:</p>", 2)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblDraftNotValid"));
    }

    @Test
    void soloSeEditaLaUltimaVersionPorRevisar() {
        DraftET aprobado = borrador(5, 2);
        when(support.find(5)).thenReturn(aprobado);
        doThrow(new BusinessException("lblDraftNotPending", "ya aprobado")).when(support).requireLatestPending(aprobado);

        assertThatThrownBy(() -> service.update(5, edicion("Asunto", "<p>Texto</p>", 2)))
                .isInstanceOf(BusinessException.class);
        verify(draftDAO, never()).save(any());
    }

    @Test
    void descartarGuardaElMotivoYQuienLoHizo() {
        DraftET actual = borrador(5, 2);
        when(support.find(5)).thenReturn(actual);
        MasterTableET descartado = estado(DraftStatus.DISCARDED);
        when(support.status(DraftStatus.DISCARDED)).thenReturn(descartado);
        DiscardDraftRequestDTO motivo = new DiscardDraftRequestDTO();
        motivo.setReason("  El contacto ya es cliente  ");

        service.discard(5, motivo);

        assertThat(actual.getStatus()).isSameAs(descartado);
        assertThat(actual.getDiscardReason()).isEqualTo("El contacto ya es cliente");
        assertThat(actual.getLastUpdatedBy()).isSameAs(revisora);
        assertThat(actual.getLastUpdatedDate()).isSameAs(hoy);
        verify(draftDAO).save(actual);
    }

    @Test
    void elHistorialTraeTodasLasVersiones() {
        List<DraftET> versiones = List.of(borrador(6, 2), borrador(5, 1));
        when(draftDAO.findVersions(900)).thenReturn(versiones);
        when(support.describe(versiones)).thenReturn(List.of(new DraftResponseDTO(), new DraftResponseDTO()));

        assertThat(service.versions(900)).hasSize(2);
        assertThat(Optional.of(versiones.get(0).getVersion())).contains(2);
    }
}
