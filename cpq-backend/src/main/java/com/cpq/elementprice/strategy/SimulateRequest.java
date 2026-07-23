package com.cpq.elementprice.strategy;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/** 策略试算请求（task-0722 · B10，契约见 api.md §6）。draft 可选：传了按草稿试算，不传按库中已存策略。*/
public class SimulateRequest {
    public String customerNo;
    public LocalDate baseDate;
    public DraftBundle draft;

    /** draft 结构与 §5.1 响应的 default/exceptions 同构（id 可为 null）。*/
    public static class DraftBundle {
        @JsonProperty("default")
        public StrategyUpsertRequest default_;
        public List<StrategyUpsertRequest> exceptions;
    }
}
