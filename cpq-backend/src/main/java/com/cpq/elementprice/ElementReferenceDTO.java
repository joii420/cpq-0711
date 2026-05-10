package com.cpq.elementprice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO for element reference price (admin-entered MANUAL rows in element_daily_price).
 * Used as the response for GET /reference and POST /manual.
 */
public class ElementReferenceDTO {

    public String elementName;
    public BigDecimal price;
    public String currency;
    public String unit;
    public LocalDate priceDate;
    /** Full name of the user who entered this price (from user.full_name). */
    public String enteredByName;
    public String note;

    public ElementReferenceDTO() {}

    public ElementReferenceDTO(String elementName, BigDecimal price, String currency,
                                String unit, LocalDate priceDate,
                                String enteredByName, String note) {
        this.elementName = elementName;
        this.price = price;
        this.currency = currency;
        this.unit = unit;
        this.priceDate = priceDate;
        this.enteredByName = enteredByName;
        this.note = note;
    }
}
