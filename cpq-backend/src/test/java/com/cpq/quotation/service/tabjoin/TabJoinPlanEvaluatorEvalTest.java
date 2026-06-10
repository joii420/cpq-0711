package com.cpq.quotation.service.tabjoin;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinPlanEvaluatorEvalTest {

    private final TabJoinPlanEvaluator ev = new TabJoinPlanEvaluator();
    private Map<String,Object> w(Object... kv){ Map<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<kv.length;i+=2) m.put((String)kv[i],kv[i+1]); return m; }
    private void bd(String e, BigDecimal a){ assertEquals(0,new BigDecimal(e).compareTo(a),"got "+a); }

    @Test void bare_detail_term_autosum() {
        var rows = List.of(w("投料.金额",100,"加工.工时",4), w("投料.金额",60,"加工.工时",0), w("投料.金额",0,"加工.工时",5));
        bd("400", ev.evalExpression("[投料.金额] * [加工.工时]", rows, Map.of()));
    }
    @Test void explicit_avg_once() {
        var rows = List.of(w("投料.工时",4), w("投料.工时",5));
        bd("4.5", ev.evalExpression("AVG([投料.工时])", rows, Map.of()));
    }
    @Test void avg_times_bare_detail() {
        var rows = List.of(w("投料.工时",4,"投料.数量",2), w("投料.工时",5,"投料.数量",3));
        bd("22.5", ev.evalExpression("AVG([投料.工时]) * [投料.数量]", rows, Map.of()));
    }
    @Test void agg_plus_scalar_total_once() {
        var rows = List.of(w("投料.金额",100), w("投料.金额",60));
        var scalars = Map.of("回料(总计)", new BigDecimal("39"));
        bd("139", ev.evalExpression("MAX([投料.金额]) + [回料(总计)]", rows, scalars));
    }
    @Test void mixed_sum_term_plus_scalar() {
        var rows = List.of(w("投料.金额",100,"加工.工时",4), w("投料.金额",60,"加工.工时",0), w("投料.金额",0,"加工.工时",5));
        var scalars = Map.of("回料(总计)", new BigDecimal("39"));
        bd("439", ev.evalExpression("[投料.金额] * [加工.工时] + [回料(总计)]", rows, scalars));
    }
    @Test void column_total_token() {
        var scalars = Map.of("投料.金额(总计)", new BigDecimal("160"));
        bd("160", ev.evalExpression("[投料.金额(总计)]", List.of(), scalars));
    }
    @Test void missing_detail_zero_div_one() {
        var rows = List.of(w("a.x",10));
        bd("10", ev.evalExpression("SUM([a.x] / [a.y])", rows, Map.of()));
    }

    @Test void sign_propagation() {
        // 3 行: 投料.金额100/加工.工时4 ; 60/0 ; 0/5 → 逐行: 100*4=400, 60*0=0, 0*5=0 → sum=400
        // 100 - sum([投料.金额]*[加工.工时]) = 100 - 400 = -300
        var rows3 = List.of(
            w("投料.金额", 100, "加工.工时", 4),
            w("投料.金额",  60, "加工.工时", 0),
            w("投料.金额",   0, "加工.工时", 5));
        bd("-300", ev.evalExpression("100 - [投料.金额] * [加工.工时]", rows3, Map.of()));
    }

    @Test void leading_minus() {
        // 表达式首项是负号：首项 sign=-1，text="[a.x]"，单行 160 → result = -160
        bd("-160", ev.evalExpression("-[a.x]", List.of(w("a.x", 160)), Map.of()));
    }

    @Test void paren_not_split() {
        // SUM([a.x]) * (1 + 2)：SUM=60，(1+2)=3，60*3=180
        bd("180", ev.evalExpression("SUM([a.x]) * (1 + 2)",
            List.of(w("a.x", 10), w("a.x", 50)), Map.of()));
    }
}
