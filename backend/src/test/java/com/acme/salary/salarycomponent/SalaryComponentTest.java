package com.acme.salary.salarycomponent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.error.ValidationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Component definition invariants. Pure — no Spring, no database. */
class SalaryComponentTest {

    private static SalaryComponent flatEarning(String code, String value) {
        return new SalaryComponent(code, code, ComponentType.EARNING, CalculationType.FLAT,
                new BigDecimal(value), true);
    }

    @Test
    void canonicalisesTheCode() {
        assertThat(flatEarning("hra", "0").getCode()).isEqualTo("HRA");
    }

    @Test
    void rejectsACodeWithPunctuationOrSpaces() {
        // Codes travel into payslips and CSV exports, so they stay machine-friendly.
        assertThatThrownBy(() -> flatEarning("special allowance", "0"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> flatEarning("HRA-2", "0")).isInstanceOf(ValidationException.class);
        assertThatCode(() -> flatEarning("SPECIAL_2", "0")).doesNotThrowAnyException();
    }

    @Test
    void rejectsABlankCodeOrName() {
        assertThatThrownBy(() -> flatEarning(" ", "0")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new SalaryComponent("HRA", " ", ComponentType.EARNING,
                CalculationType.FLAT, BigDecimal.ZERO, true))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsAMissingTypeOrCalculationType() {
        assertThatThrownBy(() -> new SalaryComponent("HRA", "HRA", null,
                CalculationType.FLAT, BigDecimal.ZERO, true))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new SalaryComponent("HRA", "HRA", ComponentType.EARNING,
                null, BigDecimal.ZERO, true))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsANegativeValue() {
        assertThatThrownBy(() -> flatEarning("HRA", "-1")).isInstanceOf(ValidationException.class);
    }

    @Test
    void capsAPercentageComponentAtOneHundred() {
        assertThatThrownBy(() -> new SalaryComponent("PF", "Provident Fund", ComponentType.DEDUCTION,
                CalculationType.PERCENT_OF_BASIC, new BigDecimal("101"), false))
                .isInstanceOf(ValidationException.class);
        assertThatCode(() -> new SalaryComponent("PF", "Provident Fund", ComponentType.DEDUCTION,
                CalculationType.PERCENT_OF_BASIC, new BigDecimal("100"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotCapAFlatComponentAtOneHundred() {
        // 100 is a percentage ceiling, not a money ceiling.
        assertThatCode(() -> flatEarning("BASIC", "50000")).doesNotThrowAnyException();
    }

    @Test
    void normalisesValuesToMoneyScale() {
        assertThat(flatEarning("HRA", "20000.005").getDefaultValue()).isEqualByComparingTo("20000.01");
    }

    @Test
    void appliesTheSameRulesToAValueAssignedByAStructure() {
        SalaryComponent providentFund = new SalaryComponent("PF", "Provident Fund",
                ComponentType.DEDUCTION, CalculationType.PERCENT_OF_BASIC, new BigDecimal("12"), false);

        assertThat(providentFund.validateAssignedValue(new BigDecimal("10"))).isEqualByComparingTo("10.00");
        assertThatThrownBy(() -> providentFund.validateAssignedValue(new BigDecimal("120")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> providentFund.validateAssignedValue(null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void knowsWhetherItIsBasic() {
        assertThat(flatEarning("BASIC", "50000").isBasic()).isTrue();
        assertThat(flatEarning("HRA", "20000").isBasic()).isFalse();
    }

    @Test
    void startsActiveAndCanBeRetired() {
        SalaryComponent component = flatEarning("HRA", "0");
        assertThat(component.isActive()).isTrue();

        component.deactivate();

        assertThat(component.isActive()).isFalse();
    }
}
