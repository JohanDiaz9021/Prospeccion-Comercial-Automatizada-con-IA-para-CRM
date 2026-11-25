package co.com.gtc.prospeccion.draft.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.constants.MasterTableCodes.DraftStatus;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.exception.ConflictException;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.MasterTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.draft.dto.request.DraftPreviewRequestDTO;
import co.com.gtc.prospeccion.draft.dto.response.DraftResponseDTO;
import co.com.gtc.prospeccion.draft.entities.DraftET;
import co.com.gtc.prospeccion.draft.mapper.DraftMapperImpl;
import co.com.gtc.prospeccion.draft.persistence.DraftDAO;
import co.com.gtc.prospeccion.integration.gemini.GeminiClient;
import co.com.gtc.prospeccion.integration.gemini.GeminiException;
import co.com.gtc.prospeccion.integration.gemini.dto.DraftContact;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiDraftResult;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.repository.EvidenceRepository;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

class DraftServiceImplTest {

    static final int CONTACTO = 900;
    static final String PERFIL = "{\"company\": {\"name\": \"GTC\", \"valueProposition\": \"Conectamos sistemas.\"}}";

    /** El ejemplo de docs/negocio/mensaje-base.md en HTML, con un script que debe desaparecer. */
    static final String BUEN_HTML = """
            <p>Buenos días, Laura:</p>
            <p>En una clínica del tamaño de Clínica Los Andes, la dirección de tecnología suele tener que mantener al día varios sistemas (historia clínica, facturación, inventarios) que no siempre comparten la información.</p>
            <p>En GTC ayudamos a empresas medianas colombianas a conectar sus sistemas y automatizar procesos manuales para que operen más rápido y con menos errores. Por ejemplo:</p>
            <ul>
            <li>Integramos el ERP, el CRM y la facturación para que la información esté en un solo lugar.</li>
            <li>Automatizamos tareas repetitivas con software a la medida e inteligencia artificial.</li>
            <li>Damos soporte y mantenimiento a plataformas críticas cuando el equipo interno no da abasto.</li>
            </ul>
            <p>¿Le parece si conversamos 20 minutos la próxima semana para ver si algo de esto le sirve a Clínica Los Andes?</p>
            <p>Cordialmente,</p>
            <p>Equipo Comercial GTC</p><script>alert(1)</script>
            """;

    private final DraftDAO draftDAO = mock(DraftDAO.class);
    private final TenantConfigRepository configRepository = mock(TenantConfigRepository.class);
    private final EvidenceRepository evidenceRepository = mock(EvidenceRepository.class);
    private final MasterTableRepository masterTable = mock(MasterTableRepository.class);
    private final DateTableResolver dateTable = mock(DateTableResolver.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final SuppressionService suppression = mock(SuppressionService.class);
    private final TenantConfigET config = new TenantConfigET();
    private final MasterTableET pendiente = new MasterTableET();
    private final DateTableET hoy = new DateTableET();
    private final UserET usuarioPeticion = new UserET();
    private DraftServiceImpl service;

    static String mensajeBase() throws IOException {
        return Files.readString(Path.of("docs/negocio/mensaje-base.txt"), StandardCharsets.UTF_8);
    }

    static EvidenceET evidencia(String source, String field, String value) {
        EvidenceET e = new EvidenceET();
        e.setPmLeafletContactId(CONTACTO);
        e.setSource(source);
        e.setField(field);
        e.setValue(value);
        return e;
    }

    static GeminiDraftResult borrador(String subject, String html) {
        return GeminiDraftResult.builder().subject(subject).bodyHtml(html).model("gemini-2.5-flash")
                .promptVersion("draft-v1").build();
    }

    @BeforeEach
    void preparar() throws IOException {
        config.setBaseMessage(mensajeBase());
        config.setProfileOfInterest(PERFIL);
        when(configRepository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(config));
        when(evidenceRepository.findByPmLeafletContactId(CONTACTO)).thenReturn(List.of(
                evidencia("HUNTER", EvidenceFields.FIRST_NAME, "Otro nombre"),
                evidencia("APOLLO", EvidenceFields.FIRST_NAME, "Laura"),
                evidencia("APOLLO", EvidenceFields.TITLE, "Directora de Tecnología"),
                evidencia("APOLLO", EvidenceFields.ORGANIZATION_NAME, "Clínica Los Andes"),
                evidencia("APOLLO", EvidenceFields.INDUSTRY, "hospital & health care"),
                evidencia("APOLLO", EvidenceFields.COMPANY_SIZE, " "),
                evidencia("APOLLO", EvidenceFields.ORGANIZATION_CITY, "Bogota")));
        ReflectionTestUtils.setField(pendiente, "tableCode", DraftStatus.TABLECODE);
        ReflectionTestUtils.setField(pendiente, "code", DraftStatus.PENDING_REVIEW);
        when(masterTable.findFirstByTableCodeAndCode(DraftStatus.TABLECODE, DraftStatus.PENDING_REVIEW))
                .thenReturn(Optional.of(pendiente));
        when(dateTable.today()).thenReturn(hoy);
        when(currentUser.getCurrentUser()).thenReturn(usuarioPeticion);
        when(draftDAO.findLatestByContact(CONTACTO)).thenReturn(Optional.empty());
        when(draftDAO.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new DraftServiceImpl(draftDAO, new DraftMapperImpl(), configRepository, evidenceRepository,
                masterTable, dateTable, currentUser, userRepository, gemini, new ObjectMapper(),
                mock(DraftSupport.class), suppression);
    }

    private DraftET guardado() {
        ArgumentCaptor<DraftET> captor = ArgumentCaptor.forClass(DraftET.class);
        verify(draftDAO).save(captor.capture());
        return captor.getValue();
    }

    // ---------- generate ----------

    @Test
    void guardaUnaVersionNuevaPendienteConElHtmlLimpio() {
        DraftET anterior = new DraftET();
        anterior.setVersion(2);
        when(draftDAO.findLatestByContact(CONTACTO)).thenReturn(Optional.of(anterior));
        UserET autor = new UserET();
        when(userRepository.getReferenceById(7)).thenReturn(autor);
        when(gemini.draftEmail(anyString(), anyString(), any()))
                .thenReturn(borrador("ASUNTO: RE: Clínica Los Andes: sistemas conectados", BUEN_HTML));

        DraftResponseDTO respuesta = service.generate(CONTACTO, 7);

        DraftET d = guardado();
        assertThat(d.getVersion()).isEqualTo(3);
        assertThat(d.getPmLeafletContactId()).isEqualTo(CONTACTO);
        assertThat(d.getSubject()).isEqualTo("Clínica Los Andes: sistemas conectados");
        assertThat(d.getBody()).startsWith("<p>Buenos días, Laura:</p>").doesNotContain("script");
        assertThat(d.getStatus()).isSameAs(pendiente);
        assertThat(d.getEditedByHuman()).isFalse();
        assertThat(d.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(d.getPromptVersion()).isEqualTo("draft-v1");
        assertThat(d.getCreatedBy()).isSameAs(autor);
        assertThat(d.getCreatedDate()).isSameAs(hoy);
        assertThat(respuesta.getStatusCode()).isEqualTo(DraftStatus.PENDING_REVIEW);
        assertThat(respuesta.getVersion()).isEqualTo(3);
        assertThat(respuesta.getBodyText()).startsWith("Buenos días, Laura:\n\nEn una clínica");
        assertThat(respuesta.getWarnings()).isEmpty();
        assertThat(respuesta.getWords()).isBetween(90, 160);
    }

    @Test
    void geminiSoloVeLosDatosConEvidenciaYGanaApollo() {
        when(gemini.draftEmail(anyString(), anyString(), any())).thenReturn(borrador("Asunto", BUEN_HTML));

        service.generate(CONTACTO, null);

        ArgumentCaptor<DraftContact> contacto = ArgumentCaptor.forClass(DraftContact.class);
        verify(gemini).draftEmail(eq(config.getBaseMessage()), eq(PERFIL), contacto.capture());
        assertThat(contacto.getValue()).isEqualTo(new DraftContact("Laura", "Directora de Tecnología",
                "Clínica Los Andes", "hospital & health care", null, "Bogota"));
        assertThat(guardado().getCreatedBy()).isSameAs(usuarioPeticion); // sin userId, el de la petición
    }

    @Test
    void siQuedanMarcasSinLlenarPideOtraRedaccion() {
        when(gemini.draftEmail(anyString(), anyString(), any()))
                .thenReturn(borrador("Asunto", BUEN_HTML.replace("Laura", "{{nombre}}")))
                .thenReturn(borrador("Asunto", BUEN_HTML));

        service.generate(CONTACTO, null);

        verify(gemini, times(2)).draftEmail(anyString(), anyString(), any());
        assertThat(guardado().getBody()).contains("Laura");
    }

    @Test
    void unaRespuestaInvalidaTambienSeReintenta() {
        when(gemini.draftEmail(anyString(), anyString(), any()))
                .thenThrow(new GeminiException.InvalidResponse("no es JSON"))
                .thenReturn(borrador("Asunto", BUEN_HTML));

        service.generate(CONTACTO, null);

        verify(draftDAO).save(any());
    }

    @Test
    void siLasDosRedaccionesFallanNoGuardaNada() {
        when(gemini.draftEmail(anyString(), anyString(), any()))
                .thenReturn(borrador("Asunto", BUEN_HTML.replace("Equipo Comercial GTC", "Juan")));

        assertThatThrownBy(() -> service.generate(CONTACTO, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblDraftNotValid"));
        verify(gemini, times(DraftServiceImpl.MAX_ATTEMPTS)).draftEmail(anyString(), anyString(), any());
        verify(draftDAO, never()).save(any());
    }

    @Test
    void siGeminiBloqueaNoReintentaNiGuarda() {
        when(gemini.draftEmail(anyString(), anyString(), any())).thenThrow(new GeminiException.Blocked("SAFETY"));

        assertThatThrownBy(() -> service.generate(CONTACTO, null)).isInstanceOf(GeminiException.Blocked.class);
        verify(gemini, times(1)).draftEmail(anyString(), anyString(), any());
        verify(draftDAO, never()).save(any());
    }

    @Test
    void losAvisosLleganAQuienRevisa() {
        when(gemini.draftEmail(anyString(), anyString(), any())).thenReturn(borrador("Asunto",
                BUEN_HTML.replace("con menos errores", "con un 40 % menos de errores")));

        assertThat(service.generate(CONTACTO, null).getWarnings()).containsExactly("NUMBER_NOT_IN_DATA");
    }

    @Test
    void sinMensajeBaseNoLlamaAGemini() {
        config.setBaseMessage("  ");

        assertThatThrownBy(() -> service.generate(CONTACTO, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblBaseMessageMissing"));
        verify(gemini, never()).draftEmail(any(), any(), any());
    }

    @Test
    void sinEvidenciaNoHayQueRedactar() {
        when(evidenceRepository.findByPmLeafletContactId(CONTACTO)).thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(CONTACTO, null)).isInstanceOf(NotFoundException.class);
        verify(gemini, never()).draftEmail(any(), any(), any());
    }

    @Test
    void siYaHayUnaVersionAprobadaNoSeRedactaOtra() {
        when(draftDAO.hasApprovedVersion(CONTACTO)).thenReturn(true);

        assertThatThrownBy(() -> service.generate(CONTACTO, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblDraftAlreadyApproved"));
        verify(gemini, never()).draftEmail(any(), any(), any());
    }

    @Test
    void dosRedaccionesALaVezDanConflicto() {
        when(gemini.draftEmail(anyString(), anyString(), any())).thenReturn(borrador("Asunto", BUEN_HTML));
        when(draftDAO.save(any())).thenThrow(new DataIntegrityViolationException("UK_LA_DRAFT_IDX01"));

        assertThatThrownBy(() -> service.generate(CONTACTO, null)).isInstanceOf(ConflictException.class);
    }

    // ---------- preview ----------

    @Test
    void laPruebaUsaElMensajePropioYNoGuarda() {
        when(gemini.draftEmail(anyString(), anyString(), any())).thenReturn(borrador("Asunto", BUEN_HTML));
        DraftPreviewRequestDTO request = new DraftPreviewRequestDTO();
        request.setBaseMessage("ASUNTO: x\n\nHola {{nombre}}\n\nEquipo Comercial GTC");
        DraftPreviewRequestDTO.Contact contacto = new DraftPreviewRequestDTO.Contact();
        contacto.setFirstName(" Laura ");
        contacto.setTitle("");
        request.setContact(contacto);

        DraftResponseDTO respuesta = service.preview(request);

        verify(gemini).draftEmail(eq(request.getBaseMessage()), eq(PERFIL),
                eq(new DraftContact("Laura", null, null, null, null, null)));
        verify(draftDAO, never()).save(any());
        assertThat(respuesta.getId()).isNull();
        assertThat(respuesta.getBody()).doesNotContain("script");
        assertThat(respuesta.getPromptVersion()).isEqualTo("draft-v1");
    }

    @Test
    void laPruebaSinMensajePropioUsaElDeLaConfiguracion() {
        when(gemini.draftEmail(anyString(), anyString(), any())).thenReturn(borrador("Asunto", BUEN_HTML));
        DraftPreviewRequestDTO request = new DraftPreviewRequestDTO();
        request.setContact(new DraftPreviewRequestDTO.Contact());

        service.preview(request);

        verify(gemini).draftEmail(eq(config.getBaseMessage()), eq(PERFIL), any());
    }

    @Test
    void laPruebaSinMensajeEnNingunLadoFalla() {
        when(configRepository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());
        DraftPreviewRequestDTO request = new DraftPreviewRequestDTO();
        request.setContact(new DraftPreviewRequestDTO.Contact());

        assertThatThrownBy(() -> service.preview(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblBaseMessageMissing"));
    }

    @Test
    void noSeRedactaParaQuienSeDioDeBaja() {
        when(evidenceRepository.findByPmLeafletContactId(CONTACTO)).thenReturn(List.of(
                evidencia("APOLLO", EvidenceFields.FIRST_NAME, "Laura"),
                evidencia("HUNTER", EvidenceFields.EMAIL, "Laura@Clinica-Demo.example")));
        when(suppression.isSuppressed("laura@clinica-demo.example")).thenReturn(true);

        assertThatThrownBy(() -> service.generate(CONTACTO, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblContactSuppressed"));
        verify(gemini, never()).draftEmail(any(), any(), any());
    }
}
