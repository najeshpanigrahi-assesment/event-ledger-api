package com.eventledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Computed account balance response")
public final class BalanceResponse {

    @Schema(example = "acct-123")
    private String accountId;

    @Schema(description = "Net balance = sum(CREDIT amounts) − sum(DEBIT amounts)", example = "250.00")
    private BigDecimal balance;

    @Schema(example = "USD")
    private String currency;
}
