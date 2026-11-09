package com.adwiiseagent.batch.domain.enumeration;

/**
 * Copy of the service PaymentProvider enum.
 * payment_provider_config.provider_name stays a string column; Stripe fee, tax, and refund jobs
 * keep querying STRIPE only. RAZORPAY and NTT_DATA exist so new provider rows are valid names.
 */
public enum PaymentProvider {
    STRIPE,
    PAYPAL,
    ZEFFY,
    ZELLE_MANUAL,
    VENMO_MANUAL,
    CASH_APP_MANUAL,
    CASH,
    CHECK,
    OTHER_MANUAL,
    REVOLUT,
    CEFI_CHARITY,
    GIVEBUTTER,
    RAZORPAY,
    NTT_DATA,
}
