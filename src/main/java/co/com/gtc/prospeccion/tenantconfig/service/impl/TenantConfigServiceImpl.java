package co.com.gtc.prospeccion.tenantconfig.service.impl;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import co.com.gtc.prospeccion.common.constants.MessageKeys;
import co.com.gtc.prospeccion.common.exception.BusinessException;
import co.com.gtc.prospeccion.common.exception.ConflictException;
import co.com.gtc.prospeccion.common.exception.NotFoundException;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.security.CurrentUserProvider;
import co.com.gtc.prospeccion.tenantconfig.dto.TenantConfigDTO;
import co.com.gtc.prospeccion.tenantconfig.dto.TenantConfigVersionDTO;
import co.com.gtc.prospeccion.tenantconfig.entities.TenantConfigET;
import co.com.gtc.prospeccion.tenantconfig.mapper.TenantConfigMapper;
import co.com.gtc.prospeccion.tenantconfig.repository.TenantConfigRepository;
import co.com.gtc.prospeccion.tenantconfig.service.TenantConfigService;

/**
 * Configuración del robot por tenant (tarea 6.2). Se versiona: cada guardado crea una fila nueva vigente
 * (FLGACTIVE = 1) y apaga la anterior, así queda el historial y cada búsqueda guarda con qué versión corrió.
 */
@Service
public class TenantConfigServiceImpl implements TenantConfigService {

    private static final Logger log = LoggerFactory.getLogger(TenantConfigServiceImpl.class);

    /** Marca de los documentos de negocio para lo que aún falta confirmar. */
    static final String PENDING_MARK = "[validar]";

    /** Filas máximas por página del historial. */
    static final int MAX_PAGE_SIZE = 50;

    private final TenantConfigRepository repository;
    private final TenantConfigMapper mapper;
    private final CurrentUserProvider currentUserProvider;
    private final DateTableResolver dateTableResolver;
    private final ObjectMapper objectMapper;

    public TenantConfigServiceImpl(TenantConfigRepository repository, TenantConfigMapper mapper,
            CurrentUserProvider currentUserProvider, DateTableResolver dateTableResolver, ObjectMapper objectMapper) {
        this.repository = repository;
        this.mapper = mapper;
        this.currentUserProvider = currentUserProvider;
        this.dateTableResolver = dateTableResolver;
        this.objectMapper = objectMapper;
    }

    /** Configuración vigente; si el tenant aún no tiene, valores por defecto con id null. */
    @Override
    @Transactional(readOnly = true)
    public TenantConfigDTO get() {
        return repository.findFirstByFlgActiveTrueOrderByIdDesc().map(mapper::toDTO).orElseGet(() -> {
            TenantConfigDTO defaults = new TenantConfigDTO();
            defaults.setScoreThreshold(DEFAULT_SCORE_THRESHOLD);
            return defaults;
        });
    }

    @Override
    @Transactional
    public TenantConfigDTO update(TenantConfigDTO request) {
        validate(request);

        Optional<TenantConfigET> current = repository.findFirstByFlgActiveTrueOrderByIdDesc();
        Integer currentId = current.map(TenantConfigET::getId).orElse(null);
        if (!Objects.equals(currentId, request.getId())) {
            throw new ConflictException(MessageKeys.CONFLICT,
                    "Se editaba la versión " + request.getId() + " pero la vigente es " + currentId);
        }

        UserET user = currentUserProvider.getCurrentUser();
        DateTableET today = dateTableResolver.today();

        // La anterior se apaga y se escribe ANTES de insertar la nueva: la base solo admite una vigente
        // (UK_LA_TENANTCONFIG_IDX01) y Hibernate, sin flush, insertaría primero.
        current.ifPresent(previous -> {
            previous.setFlgActive(false);
            previous.setLastUpdatedBy(user);
            previous.setLastUpdatedDate(today);
            repository.saveAndFlush(previous);
        });

        TenantConfigET next = new TenantConfigET();
        next.setProfileOfInterest(blankToNull(request.getProfileOfInterest()));
        next.setBaseMessage(blankToNull(request.getBaseMessage()));
        next.setScoreThreshold(request.getScoreThreshold());
        next.setDailySendLimit(request.getDailySendLimit());
        next.setMinSpacingSeconds(request.getMinSpacingSeconds());
        next.setCompanyLinkedinUrl(blankToNull(request.getCompanyLinkedinUrl()));
        // El buzón lo carga TI y no viaja al frontend: se conserva de la versión anterior.
        next.setMailboxSsmKey(current.map(TenantConfigET::getMailboxSsmKey).orElse(null));
        next.setFlgActive(true);
        next.setCreatedBy(user);
        next.setCreatedDate(today);
        try {
            next = repository.saveAndFlush(next);
        } catch (DataIntegrityViolationException e) {
            // Otra petición creó una versión vigente al mismo tiempo.
            throw new ConflictException(MessageKeys.CONFLICT, "Otra versión vigente se guardó al mismo tiempo");
        }
        log.info("Configuración del tenant actualizada: versión {} (antes {})", next.getId(), currentId);
        return mapper.toDTO(next);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TenantConfigVersionDTO> versions(Pageable pageable) {
        // El orden lo fija el historial (más nueva primero): se ignora el sort que mande el cliente.
        Pageable page = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
        return repository.findAllByOrderByIdDesc(page).map(this::toVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public TenantConfigDTO version(Integer id) {
        return repository.findById(id).map(mapper::toDTO)
                .orElseThrow(() -> new NotFoundException(MessageKeys.NOT_FOUND, "No existe la versión " + id));
    }

    private TenantConfigVersionDTO toVersion(TenantConfigET et) {
        JsonNode profile = readProfile(et.getProfileOfInterest());
        JsonNode version = profile.path("version");
        String company = profile.path("company").path("name").asText("");
        return new TenantConfigVersionDTO(et.getId(), Boolean.TRUE.equals(et.getFlgActive()),
                et.getCreatedDate() == null ? null : et.getCreatedDate().getDate(), userName(et.getCreatedBy()),
                version.canConvertToInt() ? version.asInt() : null, company.isBlank() ? null : company,
                et.getScoreThreshold(), et.getDailySendLimit(), et.getMinSpacingSeconds(),
                et.getBaseMessage() != null && !et.getBaseMessage().isBlank());
    }

    /** Perfil guardado como árbol; vacío si no hay o si una versión vieja no es JSON (no rompe el historial). */
    private JsonNode readProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode json = objectMapper.readTree(profile);
            return json == null ? MissingNode.getInstance() : json;
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }

    private static String userName(UserET user) {
        if (user == null) {
            return null;
        }
        String name = Stream.of(user.getName(), user.getLastName())
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
        return name.isEmpty() ? user.getLogin() : name;
    }

    private void validate(TenantConfigDTO request) {
        String profile = request.getProfileOfInterest();
        if (profile != null && !profile.isBlank()) {
            try {
                JsonNode json = objectMapper.readTree(profile);
                if (json == null || !json.isObject()) {
                    throw new BusinessException(MessageKeys.PROFILE_INVALID, "El perfil debe ser un objeto JSON");
                }
            } catch (JsonProcessingException e) {
                throw new BusinessException(MessageKeys.PROFILE_INVALID, "El perfil no es JSON válido");
            }
        }
        // Gemini usa estos textos tal cual: una marca [validar] terminaría en la calificación o en un correo.
        if (hasPendingMark(profile) || hasPendingMark(request.getBaseMessage())) {
            throw new BusinessException(MessageKeys.PENDING_VALIDATION,
                    "El perfil o el mensaje base tienen marcas " + PENDING_MARK + " sin resolver");
        }
    }

    private static boolean hasPendingMark(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(PENDING_MARK);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
