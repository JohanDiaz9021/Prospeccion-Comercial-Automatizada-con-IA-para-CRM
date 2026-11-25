package co.com.gtc.prospeccion.draft.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import co.com.gtc.prospeccion.draft.entities.DraftET;

public interface DraftDAO {

    DraftET save(DraftET draft);

    Optional<DraftET> findById(Integer id);

    /** Bandeja: última versión de cada contacto; statusCode vacío = todos los estados. */
    Page<DraftET> findByStatus(String statusCode, Pageable pageable);

    /** Todas las versiones del borrador de un contacto, la más nueva primero. */
    List<DraftET> findVersions(Integer pmLeafletContactId);

    /** Última versión del borrador de un contacto. */
    Optional<DraftET> findLatestByContact(Integer pmLeafletContactId);

    /** Si alguna versión del borrador del contacto ya fue aprobada. */
    boolean hasApprovedVersion(Integer pmLeafletContactId);
}
