package co.com.gtc.prospeccion.suppression.service.impl;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.com.gtc.prospeccion.common.constants.MasterTableCodes.SuppressionReason;
import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.crm.entities.UserET;
import co.com.gtc.prospeccion.crm.repository.MasterTableRepository;
import co.com.gtc.prospeccion.suppression.dto.SuppressionResponseDTO;
import co.com.gtc.prospeccion.suppression.entities.SuppressionET;
import co.com.gtc.prospeccion.suppression.repository.SuppressionRepository;
import co.com.gtc.prospeccion.suppression.service.SuppressionService;

@Service
public class SuppressionServiceImpl implements SuppressionService {

    private static final Logger log = LoggerFactory.getLogger(SuppressionServiceImpl.class);

    private final SuppressionRepository repository;
    private final MasterTableRepository masterTableRepository;
    private final DateTableResolver dateTableResolver;

    public SuppressionServiceImpl(SuppressionRepository repository, MasterTableRepository masterTableRepository,
            DateTableResolver dateTableResolver) {
        this.repository = repository;
        this.masterTableRepository = masterTableRepository;
        this.dateTableResolver = dateTableResolver;
    }

    @Override
    @Transactional
    public boolean suppress(String email, String reasonCode, Integer sourceSendId, UserET by) {
        String normalized = normalize(email);
        int at = normalized == null ? -1 : normalized.lastIndexOf('@');
        if (at <= 0 || at == normalized.length() - 1) {
            throw new IllegalArgumentException("El correo a bloquear no es válido");
        }
        if (repository.existsByEmail(normalized)) {
            return false;
        }
        SuppressionET entry = new SuppressionET();
        entry.setEmail(normalized);
        entry.setDomain(normalized.substring(at + 1));
        entry.setReason(masterTableRepository.findFirstByTableCodeAndCode(SuppressionReason.TABLECODE, reasonCode)
                .orElseThrow(() -> new IllegalStateException("Falta el motivo de bloqueo 604/" + reasonCode
                        + " en sy_mastertable (V2__catalogos_y_etiquetas.sql)")));
        entry.setSendQueueId(sourceSendId);
        entry.setCreatedBy(by);
        entry.setCreatedDate(dateTableResolver.today());
        repository.save(entry);
        log.info("Correo bloqueado (motivo {}, envío {})", reasonCode, sourceSendId); // nunca el correo en el log
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSuppressed(String email) {
        String normalized = normalize(email);
        return normalized != null && repository.existsByEmail(normalized);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SuppressionResponseDTO> list(Pageable pageable) {
        return repository.findAllByOrderByIdDesc(pageable).map(s -> new SuppressionResponseDTO(
                s.getId(), s.getEmail(), s.getDomain(), s.getReason().getCode(), s.getReason().getValue(),
                s.getCreatedDate() == null ? null : s.getCreatedDate().getDate(), s.getSendQueueId()));
    }

    private static String normalize(String email) {
        return email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
