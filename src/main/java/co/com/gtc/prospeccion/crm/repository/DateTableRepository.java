package co.com.gtc.prospeccion.crm.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.crm.entities.DateTableET;

@Repository
public interface DateTableRepository extends JpaRepository<DateTableET, Integer> {

    /** Por la columna DATE, para no depender del formato de DATECHAR (sin confirmar). */
    Optional<DateTableET> findByDate(LocalDate date);
}
