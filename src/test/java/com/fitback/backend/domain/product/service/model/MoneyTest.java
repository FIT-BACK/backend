package com.fitback.backend.domain.product.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void createsMoneyWithNonNegativeAmountAndIsoCurrency() {
        Money money = new Money(new BigDecimal("28900.00"), "KRW");

        assertThat(money.amount()).isEqualByComparingTo("28900.00");
        assertThat(money.currency()).isEqualTo("KRW");
    }

    @Test
    void rejectsNegativeAmountAndInvalidCurrency() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-0.01"), "KRW"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, "krw"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, "ABC"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmountOutsideDecimalContract() {
        assertThatThrownBy(() -> new Money(new BigDecimal("0.001"), "KRW"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(new BigDecimal("100000000000000000.00"), "KRW"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
