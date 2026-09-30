package lab.payments.validationservice;

import org.springframework.data.jpa.repository.JpaRepository;

interface FxRateRepository extends JpaRepository<FxRate, String> {
}
