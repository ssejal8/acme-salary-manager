package com.acme.salary.salarystructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarycomponent.SalaryComponent;
import com.acme.salary.support.EmployeeFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The revision lifecycle: what a structure allows, what it refuses, and which period it
 * governs. Pure — these rules are the reason a raise does not rewrite last month's pay.
 */
class SalaryStructureTest {

    private static final Long EMPLOYEE_ID = 7L;
    private static final Long ACTOR_ID = 99L;
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);

    private final SalaryComponent basic = component(1L, "BASIC", ComponentType.EARNING, CalculationType.FLAT);
    private final SalaryComponent hra = component(2L, "HRA", ComponentType.EARNING, CalculationType.FLAT);
    private final SalaryComponent providentFund =
            component(3L, "PF", ComponentType.DEDUCTION, CalculationType.PERCENT_OF_BASIC);

    private static SalaryComponent component(
            long id, String code, ComponentType type, CalculationType calculationType) {
        return EmployeeFixtures.withId(
                new SalaryComponent(code, code, type, calculationType, BigDecimal.ZERO, true), id);
    }

    private SalaryStructure structure(LocalDate effectiveFrom, String overrideReason) {
        SalaryStructure structure = new SalaryStructure(EMPLOYEE_ID, effectiveFrom, ACTOR_ID, overrideReason);
        structure.addComponent(basic, new BigDecimal("50000"));
        structure.addComponent(hra, new BigDecimal("20000"));
        structure.addComponent(providentFund, new BigDecimal("12"));
        return structure;
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        void startsAsTheOpenRevision() {
            SalaryStructure structure = structure(APRIL, null);

            assertThat(structure.isCurrent()).isTrue();
            assertThat(structure.getSupersededOn()).isNull();
            assertThat(structure.getEffectiveFrom()).isEqualTo(APRIL);
            assertThat(structure.getEmployeeId()).isEqualTo(EMPLOYEE_ID);
            assertThat(structure.getCreatedBy()).isEqualTo(ACTOR_ID);
        }

        @Test
        void requiresAnEmployeeAnEffectiveDateAndAnAuthor() {
            // created_by is NOT NULL in the schema: a pay change always has an author.
            assertThatThrownBy(() -> new SalaryStructure(null, APRIL, ACTOR_ID, null))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> new SalaryStructure(EMPLOYEE_ID, null, ACTOR_ID, null))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> new SalaryStructure(EMPLOYEE_ID, APRIL, null, null))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void treatsABlankOverrideReasonAsNoReasonAtAll() {
            // Otherwise "   " would silently satisfy the grade-band override (FR-4.3).
            assertThat(new SalaryStructure(EMPLOYEE_ID, APRIL, ACTOR_ID, "   ").getOverrideReason()).isNull();
            assertThat(new SalaryStructure(EMPLOYEE_ID, APRIL, ACTOR_ID, "").getOverrideReason()).isNull();
            assertThat(new SalaryStructure(EMPLOYEE_ID, APRIL, ACTOR_ID, null).getOverrideReason()).isNull();
        }

        @Test
        void stripsSurroundingWhitespaceFromAnOverrideReason() {
            assertThat(new SalaryStructure(EMPLOYEE_ID, APRIL, ACTOR_ID, "  Retention case  ")
                    .getOverrideReason()).isEqualTo("Retention case");
        }
    }

    @Nested
    @DisplayName("superseding")
    class Superseding {

        @Test
        void closesTheRevisionOnTheSuccessorsEffectiveDate() {
            SalaryStructure structure = structure(APRIL, null);

            structure.supersede(OCTOBER);

            assertThat(structure.getSupersededOn()).isEqualTo(OCTOBER);
            assertThat(structure.isCurrent()).isFalse();
        }

        @Test
        void refusesADateBeforeTheRevisionTakesEffect() {
            SalaryStructure structure = structure(APRIL, null);

            assertThatThrownBy(() -> structure.supersede(APRIL.minusDays(1)))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(fieldError ->
                                    assertThat(fieldError.field()).isEqualTo("effectiveFrom")));
            assertThat(structure.isCurrent()).isTrue();
        }

        @Test
        void refusesTheRevisionsOwnEffectiveDate() {
            // Two revisions cannot both govern the same day; the database says so too.
            SalaryStructure structure = structure(APRIL, null);

            assertThatThrownBy(() -> structure.supersede(APRIL)).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesAMissingDate() {
            SalaryStructure structure = structure(APRIL, null);

            assertThatThrownBy(() -> structure.supersede(null)).isInstanceOf(ValidationException.class);
        }

        @Test
        void acceptsTheDayAfterTheEffectiveDate() {
            SalaryStructure structure = structure(APRIL, null);

            assertThatCode(() -> structure.supersede(APRIL.plusDays(1))).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("which period a revision governs")
    class EffectivePeriod {

        /**
         * A revision effective 1 April and superseded on 1 October governs April to
         * September inclusive. The successor takes over on its own effective date, so the
         * supersession day itself belongs to the successor — the boundary a payroll run
         * depends on when it resolves a structure per period (FR-5.3).
         */
        @ParameterizedTest(name = "{0} is governed: {1}")
        @CsvSource({
                "2026-03-31, false",
                "2026-04-01, true",
                "2026-06-15, true",
                "2026-09-30, true",
                "2026-10-01, false",
                "2026-10-02, false",
        })
        void resolvesTheBoundariesOfAClosedRevision(LocalDate date, boolean governed) {
            SalaryStructure structure = structure(APRIL, null);
            structure.supersede(OCTOBER);

            assertThat(structure.isEffectiveOn(date)).isEqualTo(governed);
        }

        @ParameterizedTest(name = "{0} is governed: {1}")
        @CsvSource({
                "2026-03-31, false",
                "2026-04-01, true",
                "2030-01-01, true",
        })
        void anOpenRevisionGovernsEverythingFromItsEffectiveDateOnwards(LocalDate date, boolean governed) {
            assertThat(structure(APRIL, null).isEffectiveOn(date)).isEqualTo(governed);
        }
    }

    @Nested
    @DisplayName("components and totals")
    class ComponentsAndTotals {

        @Test
        void mapsEachLineOntoItsDefinition() {
            List<ComponentAmount> amounts = structure(APRIL, null).toComponentAmounts();

            assertThat(amounts).hasSize(3);
            assertThat(amounts).extracting(ComponentAmount::code).containsExactly("BASIC", "HRA", "PF");
            assertThat(amounts.get(2).type()).isEqualTo(ComponentType.DEDUCTION);
            assertThat(amounts.get(2).calculationType()).isEqualTo(CalculationType.PERCENT_OF_BASIC);
            assertThat(amounts.get(2).value()).isEqualByComparingTo("12.00");
        }

        @Test
        void computesItsOwnTotals() {
            StructureTotals totals = structure(APRIL, null).totals();

            assertThat(totals.grossMonthly()).isEqualByComparingTo("70000.00");
            assertThat(totals.totalDeductions()).isEqualByComparingTo("6000.00");
            assertThat(totals.netMonthly()).isEqualByComparingTo("64000.00");
            assertThat(totals.annualCtc()).isEqualByComparingTo("840000.00");
        }

        @Test
        void validatesAValueAgainstItsDefinitionAsItIsAdded() {
            // A percentage above 100 is caught at the point of entry, not at computation.
            SalaryStructure structure = new SalaryStructure(EMPLOYEE_ID, APRIL, ACTOR_ID, null);

            assertThatThrownBy(() -> structure.addComponent(providentFund, new BigDecimal("120")))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> structure.addComponent(basic, new BigDecimal("-1")))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void normalisesAddedValuesToMoneyScale() {
            SalaryStructure structure = new SalaryStructure(EMPLOYEE_ID, APRIL, ACTOR_ID, null);
            structure.addComponent(basic, new BigDecimal("50000.005"));

            assertThat(structure.toComponentAmounts().get(0).value()).isEqualByComparingTo("50000.01");
        }

        @Test
        void refusesNewLinesOnceItHasBeenPersisted() {
            // ADR-009: a saved revision is never edited. This is what makes FR-4.7 hold
            // for a structure a finalised payroll run has already used.
            SalaryStructure saved = EmployeeFixtures.withId(structure(APRIL, null), 500L);

            assertThatThrownBy(() -> saved.addComponent(hra, new BigDecimal("1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be modified");
        }

        @Test
        void handsOutACopyOfItsLinesRatherThanTheLiveList() {
            List<com.acme.salary.salarystructure.SalaryStructureComponent> lines =
                    structure(APRIL, null).getComponents();

            assertThatThrownBy(() -> lines.clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
