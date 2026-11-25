package co.com.gtc.prospeccion.credits.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import co.com.gtc.prospeccion.credits.entities.CreditLedgerET;

@Repository
public interface CreditLedgerRepository extends JpaRepository<CreditLedgerET, Integer> {

    /** Créditos de un proveedor desde una fecha (sy_datetable.DATE de CREATEDDATE), por ejemplo el día 1 del mes. */
    @Query("select coalesce(sum(c.credits), 0) from CreditLedgerET c "
            + "where c.provider = :provider and c.createdDate.date >= :from")
    BigDecimal sumCreditsSince(@Param("provider") String provider, @Param("from") LocalDate from);

    /** Costo estimado en USD de un proveedor desde una fecha. */
    @Query("select coalesce(sum(c.costUsd), 0) from CreditLedgerET c "
            + "where c.provider = :provider and c.createdDate.date >= :from")
    BigDecimal sumCostUsdSince(@Param("provider") String provider, @Param("from") LocalDate from);

    /** Créditos de un proveedor cargados a una campaña (la_searchrun). */
    @Query("select coalesce(sum(c.credits), 0) from CreditLedgerET c "
            + "where c.provider = :provider and c.searchRunId = :searchRunId")
    BigDecimal sumCreditsForSearchRun(@Param("provider") String provider, @Param("searchRunId") Integer searchRunId);
}
