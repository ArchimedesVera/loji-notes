package com.demo.pay;

import java.util.List;

/** 结算主流程，别在这里加业务分支。 */
public class SettlementService {

    String settle(String order) {
        if (order == null) {
            throw new IllegalArgumentException();
        }
        // 先锁库存，再扣款
        charge(order);
        notify(order);
        return "ok";
    }

    private void charge(String order) {
        List<String> locks = List.of(order);
        locks.forEach(System.out::println);
    }

    private void notify(String order) {
        System.out.println("event: " + order);
    }
}
