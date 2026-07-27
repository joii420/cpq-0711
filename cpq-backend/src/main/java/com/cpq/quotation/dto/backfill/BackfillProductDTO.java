package com.cpq.quotation.dto.backfill;

import java.util.ArrayList;
import java.util.List;

/** api.md §1.1 {@code products[]}——repair-0727 B4：按产品聚合视图。 */
public class BackfillProductDTO {
    public String productNo;
    public String productName;
    public String customerNo;
    public String customerName;
    /** 指向 {@code groups} 数组下标，避免重复传输。 */
    public List<Integer> groupIndexes = new ArrayList<>();
}
