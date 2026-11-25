package co.com.gtc.prospeccion.prospect.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.prospect.entities.ContactExtET;

@Repository
public interface ContactExtRepository extends JpaRepository<ContactExtET, Integer> {
}
