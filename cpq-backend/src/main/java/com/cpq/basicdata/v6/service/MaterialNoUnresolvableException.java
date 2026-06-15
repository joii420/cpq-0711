package com.cpq.basicdata.v6.service;

/** 料号与名称均为空、无法解析/生成料号时抛出。调用方应 recordError 跳过该行。 */
public class MaterialNoUnresolvableException extends RuntimeException {
    public MaterialNoUnresolvableException(String message) {
        super(message);
    }
}
