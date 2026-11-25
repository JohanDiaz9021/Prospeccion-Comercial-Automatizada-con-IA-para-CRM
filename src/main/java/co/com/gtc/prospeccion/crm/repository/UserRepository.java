package co.com.gtc.prospeccion.crm.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.crm.entities.UserET;

@Repository
public interface UserRepository extends JpaRepository<UserET, Integer> {

    Optional<UserET> findFirstByLogin(String login);
}
