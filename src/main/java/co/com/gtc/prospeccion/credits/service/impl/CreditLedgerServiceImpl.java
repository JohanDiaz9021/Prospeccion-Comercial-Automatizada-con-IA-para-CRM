package co.com.gtc.prospeccion.credits.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.com.gtc.prospeccion.common.util.DateTableResolver;
import co.com.gtc.prospeccion.config.PropertiesConfig.Credits;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProspeccionProperties;
import co.com.gtc.prospeccion.config.PropertiesConfig.ProviderBudget;
import co.com.gtc.prospeccion.credits.CreditCharge;
import co.com.gtc.prospeccion.credits.CreditLimitExceededException;
import co.com.gtc.prospeccion.credits.CreditLimitExceededException.Scope;
import co.com.gtc.prospeccion.credits.dto.CreditSummaryResponseDTO;
import co.com.gtc.prospeccion.credits.dto.CreditSummaryResponseDTO.Status;
import co.com.gtc.prospeccion.credits.entities.CreditLedgerET;
import co.com.gtc.prospeccion.credits.repository.CreditLedgerRepository;
import co.com.gtc.prospeccion.crm.entities.DateTableET;
import co.com.gtc.prospeccion.crm.repository.UserRepository;
import co.com.gtc.prospeccion.integration.hunter.HunterClient;
import co.com.gtc.prospeccion.credits.service.CreditLedgerService;
import co.com.gtc.prospeccion.security.CurrentUserProvider;

/**
 * Libro y topes de créditos por tenant (tarea 4.2). Los topes salen de prospeccion.credits; el libro es
 * la_creditledger de la base del tenant, así que cada tenant tiene su propio consumo y su propio tope.
 * <p>
 * El chequeo y el registro no son atómicos: dos campañas del mismo tenant en paralelo podrían pasarse por un gasto.
 * Es aceptable porque los jobs corren las campañas de un tenant una tras otra.
 */
@Service
public class CreditLedgerServiceImpl implements CreditLedgerService {

    private static final Logger log = LoggerFactory.getLogger(CreditLedgerServiceImpl.class);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    static final List<String> PROVIDERS = List.of(CreditCharge.APOLLO, CreditCharge.HUNTER);

    private final CreditLedgerRepository repository;
    private final DateTableResolver dateTableResolver;
    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;
    private final HunterClient hunterClient;
    private final Credits settings;

    public CreditLedgerServiceImpl(CreditLedgerRepository repository, DateTableResolver dateTableResolver,
            CurrentUserProvider currentUserProvider, UserRepository userRepository, HunterClient hunterClient,
            ProspeccionProperties properties) {
        this.repository = repository;
        this.dateTableResolver = dateTableResolver;
        this.currentUserProvider = currentUserProvider;
        this.userRepository = userRepository;
        this.hunterClient = hunterClient;
        this.settings = properties.getCredits();
    }

    @Override
    public void ensureAvailable(String provider, Integer searchRunId, BigDecimal credits) {
        ProviderBudget budget = budgetOf(provider);
        if (budget == null || credits == null || credits.signum() <= 0) {
            return;
        }
        if (searchRunId != null && isLimited(budget.getPerSearchRun())) {
            BigDecimal used = repository.sumCreditsForSearchRun(provider, searchRunId);
            if (used.add(credits).compareTo(budget.getPerSearchRun()) > 0) {
                throw new CreditLimitExceededException(provider, Scope.SEARCH_RUN, used, budget.getPerSearchRun());
            }
        }
        if (isLimited(budget.getMonthlyBudget())) {
            BigDecimal used = repository.sumCreditsSince(provider, firstDayOfMonth());
            if (used.add(credits).compareTo(budget.getMonthlyBudget()) > 0) {
                throw new CreditLimitExceededException(provider, Scope.MONTHLY, used, budget.getMonthlyBudget());
            }
        }
    }

    @Override
    @Transactional
    public void record(CreditCharge charge) {
        if (charge.credits() == null || charge.credits().signum() <= 0) {
            return;
        }
        ProviderBudget budget = budgetOf(charge.provider());
        CreditLedgerET entry = new CreditLedgerET();
        entry.setProvider(charge.provider());
        entry.setOperation(charge.operation());
        entry.setCredits(charge.credits());
        entry.setCostUsd(budget == null || budget.getUnitCostUsd() == null
                ? null
                : budget.getUnitCostUsd().multiply(charge.credits()).setScale(6, RoundingMode.HALF_UP));
        entry.setSearchRunId(charge.searchRunId());
        entry.setPmLeafletContactId(charge.pmLeafletContactId());
        entry.setCreatedDate(dateTableResolver.today());
        entry.setCreatedBy(charge.userId() != null
                ? userRepository.getReferenceById(charge.userId())
                : currentUserProvider.getCurrentUser());
        repository.save(entry);
    }

    @Override
    public List<CreditSummaryResponseDTO> getSummary() {
        DateTableET today = dateTableResolver.today();
        LocalDate from = today.getDate().withDayOfMonth(1);
        String period = today.getDate().format(PERIOD);
        List<CreditSummaryResponseDTO> summary = new ArrayList<>();
        for (String provider : PROVIDERS) {
            ProviderBudget budget = budgetOf(provider);
            BigDecimal used = repository.sumCreditsSince(provider, from);
            BigDecimal cost = budget.getUnitCostUsd() == null ? null : repository.sumCostUsdSince(provider, from);
            ProviderBalance balance = CreditCharge.HUNTER.equals(provider) ? hunterBalance() : ProviderBalance.NONE;
            BigDecimal remaining = balance.remaining();
            summary.add(CreditSummaryResponseDTO.builder()
                    .provider(provider)
                    .period(period)
                    .credits(used)
                    .monthlyBudget(isLimited(budget.getMonthlyBudget()) ? budget.getMonthlyBudget() : null)
                    .percentUsed(percent(used, budget.getMonthlyBudget()))
                    .perSearchRunLimit(isLimited(budget.getPerSearchRun()) ? budget.getPerSearchRun() : null)
                    .costUsd(cost)
                    .providerRemaining(remaining)
                    .providerNote(balance.note())
                    .status(status(used, budget, remaining))
                    .build());
        }
        return summary;
    }

    @Override
    public List<CreditSummaryResponseDTO> checkLowCreditAlerts() {
        List<CreditSummaryResponseDTO> alerts = getSummary().stream().filter(s -> s.getStatus() != Status.OK).toList();
        // TODO 5.2: además del log, avisar por correo al administrador del tenant cuando exista el buzón.
        alerts.forEach(a -> log.warn("Créditos de {} en {}: {} usados de {} ({} %), saldo del proveedor {}. Estado {}",
                a.getProvider(), a.getPeriod(), a.getCredits(), a.getMonthlyBudget(), a.getPercentUsed(),
                a.getProviderRemaining(), a.getStatus()));
        return alerts;
    }

    // ---------- reglas ----------

    Status status(BigDecimal used, ProviderBudget budget, BigDecimal providerRemaining) {
        if (isLimited(budget.getMonthlyBudget())) {
            if (used.compareTo(budget.getMonthlyBudget()) >= 0) {
                return Status.EXCEEDED;
            }
            Integer percent = percent(used, budget.getMonthlyBudget());
            if (percent != null && percent >= settings.getAlertPercent()) {
                return Status.WARNING;
            }
        }
        // El plan del proveedor es de toda la cuenta: si no alcanza para una campaña, se avisa aunque el tenant
        // esté lejos de su presupuesto.
        if (providerRemaining != null && isLimited(budget.getPerSearchRun())
                && providerRemaining.compareTo(budget.getPerSearchRun()) < 0) {
            return Status.WARNING;
        }
        return Status.OK;
    }

    private static Integer percent(BigDecimal used, BigDecimal limit) {
        if (!isLimited(limit)) {
            return null;
        }
        return used.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.DOWN).intValue();
    }

    /** Saldo del proveedor y, si no se pudo leer, por qué. */
    record ProviderBalance(BigDecimal remaining, String note) {
        static final ProviderBalance NONE = new ProviderBalance(null, null);
    }

    private ProviderBalance hunterBalance() {
        try {
            BigDecimal remaining = hunterClient.account().remainingCredits();
            return remaining != null
                    ? new ProviderBalance(remaining, null)
                    : new ProviderBalance(null, "Hunter no informó el saldo de la cuenta");
        } catch (RuntimeException e) {
            // Sin llave o Hunter caído: el resumen se muestra igual, sin el saldo del proveedor.
            // Los mensajes de HunterException nunca traen la llave.
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            detail = detail.length() > 200 ? detail.substring(0, 200) : detail;
            log.warn("No se pudo consultar el saldo de Hunter: {} ({})", e.getClass().getSimpleName(), detail);
            return new ProviderBalance(null, detail);
        }
    }

    private LocalDate firstDayOfMonth() {
        return dateTableResolver.today().getDate().withDayOfMonth(1);
    }

    private ProviderBudget budgetOf(String provider) {
        if (CreditCharge.APOLLO.equals(provider)) {
            return settings.getApollo();
        }
        if (CreditCharge.HUNTER.equals(provider)) {
            return settings.getHunter();
        }
        return null; // Gemini se paga por tokens: se anota pero no tiene tope de créditos.
    }

    private static boolean isLimited(BigDecimal limit) {
        return limit != null && limit.signum() > 0;
    }
}
