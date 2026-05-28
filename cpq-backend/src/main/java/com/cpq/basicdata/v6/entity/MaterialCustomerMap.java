package com.cpq.basicdata.v6.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** V6 §2 料号-客户映射。业务键 (material_no, customer_no, customer_product_no) UNIQUE。 */
@Entity
@Table(name = "material_customer_map")
public class MaterialCustomerMap extends V6BaseEntity {

    @Column(name = "material_no", nullable = false, length = 20)
    public String materialNo;

    @Column(name = "customer_no", nullable = false, length = 20)
    public String customerNo;

    @Column(name = "customer_name", length = 100)
    public String customerName;

    @Column(name = "customer_material_name", length = 100)
    public String customerMaterialName;

    @Column(name = "customer_product_no", nullable = false, length = 50)
    public String customerProductNo;

    @Column(name = "customer_drawing_no", length = 50)
    public String customerDrawingNo;

    @Column(name = "seq_no")
    public Integer seqNo;

    @Column(name = "payment_method", length = 50)
    public String paymentMethod;

    @Column(name = "base_currency", length = 10)
    public String baseCurrency;

    @Column(name = "quote_currency", length = 10)
    public String quoteCurrency;

    @Column(name = "exchange_rate", precision = 18, scale = 8)
    public BigDecimal exchangeRate;
}
