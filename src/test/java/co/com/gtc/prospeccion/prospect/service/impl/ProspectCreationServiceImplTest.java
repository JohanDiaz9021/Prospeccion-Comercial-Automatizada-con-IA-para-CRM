package co.com.gtc.prospeccion.prospect.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import co.com.gtc.prospeccion.common.constants.EvidenceFields;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.dto.FullProspectRequestDTO;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.port.CrmProspectPort;
import co.com.gtc.prospeccion.crm.port.CrmProspectPort.CreatedProspect;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloOrganizationDTO;
import co.com.gtc.prospeccion.integration.apollo.dto.ApolloPersonDTO;
import co.com.gtc.prospeccion.integration.gemini.dto.GeminiQualificationResult;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult;
import co.com.gtc.prospeccion.prospect.dto.EmailDiscoveryResult.Outcome;
import co.com.gtc.prospeccion.prospect.entities.ContactExtET;
import co.com.gtc.prospeccion.prospect.entities.EvidenceET;
import co.com.gtc.prospeccion.prospect.entities.LeafletExtET;
import co.com.gtc.prospeccion.prospect.persistence.ProspectExtDAO;
import co.com.gtc.prospeccion.prospect.repository.LeafletExtRepository;

class ProspectCreationServiceImplTest {

    private final CrmProspectPort crm = mock(CrmProspectPort.class);
    private final ProspectExtDAO dao = mock(ProspectExtDAO.class);
    private final LeafletExtRepository leaflets = mock(LeafletExtRepository.class);
    private final MasterTableRepository masters = mock(MasterTableRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DateTableResolver dates = mock(DateTableResolver.class);
    private final ProspectCreationServiceImpl service =
            new ProspectCreationServiceImpl(crm, dao, leaflets, masters, users, dates);

    @BeforeEach
    void preparar() {
        when(masters.findFirstByTableCodeAndCode(any(), any())).thenReturn(Optional.empty());
        when(users.getReferenceById(5)).thenReturn(new UserET());
        when(dates.today()).thenReturn(new DateTableET());
        when(crm.createProspect(any(), eq(5))).thenReturn(new CreatedProspect(11, 22));
    }

    private static EmailDiscoveryResult found(String source) {
        ApolloOrganizationDTO org = new ApolloOrganizationDTO();
        org.setId("org1");
        org.setName("Clínica Andina (Simulada)");
        org.setPrimaryDomain("WWW.Clinica-Andina.example");
        org.setIndustry("hospital & health care");
        org.setEstimatedNumEmployees(250);
        org.setCity("Bogota");
        ApolloPersonDTO person = new ApolloPersonDTO();
        person.setId("p1");
        person.setFirstName("Laura");
        person.setLastName("Gómez");
        person.setTitle("Directora de TI");
        person.setLinkedinUrl("https://linkedin.example/in/laura");
        person.setOrganization(org);
        return EmailDiscoveryResult.builder().outcome(Outcome.FOUND).email("Laura.Gomez@Clinica-Andina.example")
                .source(source).verification("APOLLO_VERIFIED").person(person).build();
    }

    @Test
    void creaEmpresaContactoYCorreoEnElCrmYGuardaLaEvidenciaConSuFuente() {
        GeminiQualificationResult score = GeminiQualificationResult.builder().score(88).reason("Encaja")
                .model("gemini-2.5-flash").promptVersion("qualify-v2").build();

        Integer contactId = service.createProspect(found("APOLLO"), score, 7, 3, 5);

        assertThat(contactId).isEqualTo(22);
        ArgumentCaptor<FullProspectRequestDTO> request = ArgumentCaptor.forClass(FullProspectRequestDTO.class);
        verify(crm).createProspect(request.capture(), eq(5));
        FullProspectRequestDTO r = request.getValue();
        assertThat(r.getLeaflet().getFirstName()).isEqualTo("Clínica Andina (Simulada)");
        assertThat(r.getLeaflet().getInterest()).isEqualTo("Prospección IA (puntaje 88)");
        assertThat(r.getContact().getName()).isEqualTo("Laura");
        assertThat(r.getContact().getSurname()).isEqualTo("Gómez");
        assertThat(r.getContact().getPosition()).isEqualTo("Directora de TI");
        assertThat(r.getContactMediumList()).singleElement()
                .satisfies(m -> assertThat(m.getValue()).isEqualTo("laura.gomez@clinica-andina.example"));
        assertThat(r.getContactListIds()).containsExactly(3);

        ArgumentCaptor<LeafletExtET> leaflet = ArgumentCaptor.forClass(LeafletExtET.class);
        verify(dao).saveLeafletExt(leaflet.capture());
        assertThat(leaflet.getValue().getPmLeafletId()).isEqualTo(11);
        assertThat(leaflet.getValue().getCompanyDomain()).isEqualTo("clinica-andina.example");
        assertThat(leaflet.getValue().getCompanySize()).isEqualTo("250");

        ArgumentCaptor<ContactExtET> contact = ArgumentCaptor.forClass(ContactExtET.class);
        verify(dao).saveContactExt(contact.capture());
        assertThat(contact.getValue().getScore()).isEqualTo(88);
        assertThat(contact.getValue().getSearchRunId()).isEqualTo(7);
        assertThat(contact.getValue().getEmailSource()).isEqualTo("APOLLO");
        assertThat(contact.getValue().getPromptVersion()).isEqualTo("qualify-v2");

        ArgumentCaptor<EvidenceET> evidence = ArgumentCaptor.forClass(EvidenceET.class);
        verify(dao, times(7)).saveEvidence(evidence.capture());
        Map<String, EvidenceET> byField = evidence.getAllValues().stream()
                .collect(Collectors.toMap(EvidenceET::getField, e -> e));
        assertThat(byField.get(EvidenceFields.EMAIL).getValue()).isEqualTo("laura.gomez@clinica-andina.example");
        assertThat(byField.get(EvidenceFields.TITLE).getSourceUrl()).isEqualTo("https://linkedin.example/in/laura");
        assertThat(byField.get(EvidenceFields.COMPANY_SIZE).getValue()).isEqualTo("250");
        assertThat(byField.get(EvidenceFields.ORGANIZATION_CITY).getValue()).isEqualTo("Bogota");
        assertThat(byField.values()).allSatisfy(e -> assertThat(e.getPmLeafletContactId()).isEqualTo(22));
        // Lo que Apollo no dio no queda como evidencia.
        assertThat(byField).doesNotContainKey(EvidenceFields.CITY);
    }

    @Test
    void elCorreoDeHunterQuedaConFuenteHunterYUnDominioRepetidoNoRompeLaUnicidad() {
        when(leaflets.existsByCompanyDomain("clinica-andina.example")).thenReturn(true);
        when(leaflets.existsByProviderId("org1")).thenReturn(true);

        service.createProspect(found("HUNTER"), null, 7, null, 5);

        ArgumentCaptor<EvidenceET> evidence = ArgumentCaptor.forClass(EvidenceET.class);
        verify(dao, times(7)).saveEvidence(evidence.capture());
        List<EvidenceET> email = evidence.getAllValues().stream()
                .filter(e -> EvidenceFields.EMAIL.equals(e.getField())).toList();
        assertThat(email).singleElement().satisfies(e -> assertThat(e.getSource()).isEqualTo("HUNTER"));
        ArgumentCaptor<LeafletExtET> leaflet = ArgumentCaptor.forClass(LeafletExtET.class);
        verify(dao).saveLeafletExt(leaflet.capture());
        assertThat(leaflet.getValue().getCompanyDomain()).isNull();
        assertThat(leaflet.getValue().getProviderId()).isNull();
    }

    @Test
    void soloSeCreaConUnCorreoAceptado() {
        EmailDiscoveryResult noEmail = EmailDiscoveryResult.builder().outcome(Outcome.NOT_FOUND).build();

        assertThatThrownBy(() -> service.createProspect(noEmail, null, 7, null, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
