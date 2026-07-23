package com.cpq.elementprice.pricetable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 价格矩阵（task-0722 · B6，契约见 api.md §3.2）。dates=请求区间 from~to 内的每一天（升序、稠密），缺失天由 rows[i].prices[i]=null 表示。*/
public class PriceMatrixDTO {
    public UUID sourceId;
    public String sourceName;
    public List<LocalDate> dates = new ArrayList<>();
    public List<PriceMatrixRowDTO> rows = new ArrayList<>();
}
