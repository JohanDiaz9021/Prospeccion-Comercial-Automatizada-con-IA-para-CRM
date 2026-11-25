package co.com.gtc.prospeccion.prospect.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.config.PropertiesConfig.GeminiProvider;
import co.com.gtc.prospeccion.config.PropertiesConfig.IntegrationsProperties;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPeopleSearchRequest;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloSearchPersonDTO;
import co.com.gtc.prospeccion.integration.gemini.GeminiClient;
import co.com.gtc.prospeccion.integration.gemini.GeminiException;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.integration.gemini.dto.QualificationCandidate;
import co.com.gtc.prospeccion.prospect.dto.request.QualificationPreviewRequest;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;

class QualificationServiceImplTest {

    static final String PROFILE = "{\"version\": 1}"; // sin scoring: usa las reglas por defecto

    private final TenantConfigRepository repository = mock(TenantConfigRepository.class);
    private final GeminiClient gemini = mock(GeminiClient.class);
    private QualificationServiceImpl service;

    @BeforeEach
    void preparar() {
        IntegrationsProperties integrations = mock(IntegrationsProperties.class);
        GeminiProvider provider = new GeminiProvider();
        provider.setQualificationBatchSize(10);
        when(integrations.getGemini()).thenReturn(provider);
        service = new QualificationServiceImpl(repository, gemini, new ObjectMapper(), integrations);
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(config(70)));
    }

    private static TenantConfigET config(Integer threshold) {
        TenantConfigET config = new TenantConfigET();
        config.setId(3);
        config.setProfileOfInterest(PROFILE);
        config.setScoreThreshold(threshold);
        return config;
    }

    private static ApolloSearchPersonDTO person(String title) {
        ApolloSearchPersonDTO person = new ApolloSearchPersonDTO();
        person.setId("apollo-" + title);
        person.setFirstName("Nombre Ficticio");
        person.setTitle(title);
        ApolloSearchPersonDTO.Organization organization = new ApolloSearchPersonDTO.Organization();
        organization.setName("Empresa Ficticia");
        person.setOrganization(organization);
        return person;
    }

    private static List<ApolloSearchPersonDTO> people(int count) {
        return IntStream.range(0, count).mapToObj(i -> person("Cargo " + i)).toList();
    }

    /** Respuesta de Gemini que suma {@code total}: llena title (30), sector (20), companySize (20), location (10)... */
    private static GeminiQualificationResult raw(int total) {
        Map<String, Integer> criteria = new LinkedHashMap<>();
        int[] max = {30, 20, 20, 10, 20};
        int left = total;
        for (int i = 0; i < max.length; i++) {
            int points = Math.min(left, max[i]);
            criteria.put(GeminiClient.CRITERIA.get(i), points);
            left -= points;
        }
        return GeminiQualificationResult.builder().criteria(criteria).penalty(0).reason("ok")
                .missingData(List.of()).model("gemini-2.5-flash").promptVersion("qualify-v1").build();
    }

    /** Gemini simulado: el mismo total para cada contacto del lote. */
    private void geminiAnswers(int total) {
        when(gemini.qualifyContacts(anyString(), anyList())).thenAnswer(inv -> {
            List<QualificationCandidate> batch = inv.getArgument(1);
            return batch.stream().map(c -> raw(total)).toList();
        });
    }

    @Test
    void enviaLotesDeDiezYDevuelveUnResultadoPorPersonaEnOrden() {
        geminiAnswers(80); // A

        List<GeminiQualificationResult> results = service.qualify(people(25), null);

        assertThat(results).hasSize(25).allSatisfy(r -> {
            assertThat(r.getScore()).isEqualTo(80);
            assertThat(r.getCategory()).isEqualTo("A");
            assertThat(r.getQualified()).isTrue();
        });
        verify(gemini, times(2)).qualifyContacts(anyString(), argThat(batch -> batch.size() == 10));
        verify(gemini).qualifyContacts(anyString(), argThat(batch -> batch.size() == 5));
    }

    @Test
    void usaElUmbralDeLaConfiguracionVigente() {
        geminiAnswers(65); // B, pero el umbral del tenant es 70

        GeminiQualificationResult result = service.qualify(people(1), null).get(0);

        assertThat(result.getScore()).isEqualTo(65);
        assertThat(result.getCategory()).isEqualTo("B");
        assertThat(result.getQualified()).isFalse();
    }

    @Test
    void sinUmbralConfiguradoUsaSesenta() {
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(config(null)));
        geminiAnswers(60);

        assertThat(service.qualify(people(1), null).get(0).getQualified()).isTrue();
    }

    @Test
    void geminiSoloVeCargoEmpresaYFiltrosNuncaNombreNiIds() {
        geminiAnswers(50);
        ApolloPeopleSearchRequest filters = ApolloPeopleSearchRequest.builder()
                .personLocations(List.of("Bogota, Colombia"))
                .organizationIndustries(List.of("hospital & health care"))
                .organizationNumEmployeesRanges(List.of("51,200", "201,500"))
                .qKeywords("SAP")
                .build();

        service.qualify(List.of(person("Director de TI")), filters);

        verify(gemini).qualifyContacts(argThat(PROFILE::equals), argThat(batch -> {
            QualificationCandidate c = batch.get(0);
            return c.ref().equals("1")
                    && c.title().equals("Director de TI")
                    && c.organizationName().equals("Empresa Ficticia")
                    && c.possiblePersonLocations().equals(List.of("Bogota, Colombia"))
                    && c.possibleIndustries().equals(List.of("hospital & health care"))
                    && c.possibleEmployeeRanges().equals(List.of("51,200", "201,500"))
                    && "SAP".equals(c.keywords()); // QualificationCandidate no tiene campos de nombre ni de ID
        }));
    }

    @Test
    void siGeminiFallaConUnLoteReintentaUnoPorUnoYMarcaSoloAlQueSigueFallando() {
        List<ApolloSearchPersonDTO> people = List.of(person("A"), person("B"), person("C"));
        when(gemini.qualifyContacts(anyString(), anyList())).thenAnswer(inv -> {
            List<QualificationCandidate> batch = inv.getArgument(1);
            if (batch.size() > 1) {
                throw new GeminiException.InvalidResponse("JSON cortado");
            }
            if (batch.get(0).title().equals("B")) {
                throw new GeminiException.Blocked("SAFETY");
            }
            return List.of(raw(80));
        });

        List<GeminiQualificationResult> results = service.qualify(people, null);

        assertThat(results).extracting(GeminiQualificationResult::getScore).containsExactly(80, null, 80);
        assertThat(results.get(1).getError()).isEqualTo(QualificationServiceImpl.ERROR_BLOCKED);
        assertThat(results.get(1).getQualified()).isFalse();
        verify(gemini, times(4)).qualifyContacts(anyString(), anyList()); // 1 lote + 3 individuales
    }

    @Test
    void siGeminiOmiteUnContactoSeLePreguntaSoloPorEse() {
        List<List<QualificationCandidate>> calls = new ArrayList<>();
        when(gemini.qualifyContacts(anyString(), anyList())).thenAnswer(inv -> {
            List<QualificationCandidate> batch = inv.getArgument(1);
            calls.add(batch);
            List<GeminiQualificationResult> answer = new ArrayList<>();
            batch.forEach(c -> answer.add(batch.size() > 1 && c.ref().equals("2") ? null : raw(50)));
            return answer;
        });

        List<GeminiQualificationResult> results = service.qualify(people(3), null);

        assertThat(results).extracting(GeminiQualificationResult::getScore).containsExactly(50, 50, 50);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).extracting(QualificationCandidate::ref).containsExactly("2");
    }

    @Test
    void lasFallasDeCuotaOLlaveDetienenLaCalificacion() {
        when(gemini.qualifyContacts(anyString(), anyList()))
                .thenThrow(new GeminiException.RetriesExhausted("429 tres veces"));

        assertThatThrownBy(() -> service.qualify(people(3), null))
                .isInstanceOf(GeminiException.RetriesExhausted.class);
        verify(gemini, times(1)).qualifyContacts(anyString(), anyList());
    }

    @Test
    void sinPerfilDeInteresNoLlamaAGemini() {
        TenantConfigET sinPerfil = config(60);
        sinPerfil.setProfileOfInterest(null);
        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.of(sinPerfil));

        assertThatThrownBy(() -> service.qualify(people(1), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblProfileMissing"));

        when(repository.findFirstByFlgActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.qualify(people(1), null)).isInstanceOf(BusinessException.class);
        verify(gemini, never()).qualifyContacts(any(), any());
    }

    @Test
    void laPruebaUsaElPerfilYElUmbralDeLaPeticionSinGuardarNada() {
        geminiAnswers(65);
        String perfilBorrador = "{\"version\": 2}";
        QualificationPreviewRequest request = new QualificationPreviewRequest();
        request.setProfileOfInterest(perfilBorrador);
        request.setScoreThreshold(60);
        QualificationPreviewRequest.Contact contacto = new QualificationPreviewRequest.Contact();
        contacto.setTitle("  Gerente General ");
        contacto.setIndustries(List.of("logistics & supply chain", " "));
        contacto.setSignals(List.of(""));
        request.setContacts(List.of(contacto));

        GeminiQualificationResult result = service.preview(request).get(0);

        assertThat(result.getScore()).isEqualTo(65);
        assertThat(result.getQualified()).as("umbral 60 de la petición, no 70 del tenant").isTrue();
        verify(gemini).qualifyContacts(argThat(perfilBorrador::equals), argThat(batch -> {
            QualificationCandidate c = batch.get(0);
            return c.title().equals("Gerente General")
                    && c.possibleIndustries().equals(List.of("logistics & supply chain"))
                    && c.signals() == null;
        }));
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).save(any());
    }

    @Test
    void laPruebaSinPerfilPropioUsaElVigente() {
        geminiAnswers(65);
        QualificationPreviewRequest request = new QualificationPreviewRequest();
        request.setContacts(List.of(new QualificationPreviewRequest.Contact()));

        GeminiQualificationResult result = service.preview(request).get(0);

        assertThat(result.getQualified()).as("umbral 70 del tenant").isFalse();
        verify(gemini).qualifyContacts(argThat(PROFILE::equals), anyList());
    }

    @Test
    void laPruebaConUnPerfilQueNoEsJsonSeRechaza() {
        QualificationPreviewRequest request = new QualificationPreviewRequest();
        request.setProfileOfInterest("no es json");
        request.setContacts(List.of(new QualificationPreviewRequest.Contact()));

        assertThatThrownBy(() -> service.preview(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("lblProfileInvalid"));
        verify(gemini, never()).qualifyContacts(any(), any());
    }

    @Test
    void sinPersonasNoConsultaNada() {
        assertThat(service.qualify(List.of(), null)).isEmpty();
        verify(repository, never()).findFirstByFlgActiveTrueOrderByIdDesc();
    }
}
