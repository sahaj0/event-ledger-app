package com.eventledger.account.dto.response;

import java.math.BigDecimal;

final class Amounts {
    private Amounts() {
    }

    /** Presentation only: 150.0000 -> 150.00, 0.5000 -> 0.50, 0.1234 stays 0.1234. */
    static BigDecimal display(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 2 ? stripped.setScale(2) : stripped;
    }
}
