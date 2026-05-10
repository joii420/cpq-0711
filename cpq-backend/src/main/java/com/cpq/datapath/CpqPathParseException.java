package com.cpq.datapath;

/**
 * 变量路径解析异常，当输入字符串无法被 CpqPath BNF 语法解析时抛出。
 */
public class CpqPathParseException extends RuntimeException {

    private final int position;

    public CpqPathParseException(String message, int position) {
        super(message + (position >= 0 ? " (position " + position + ")" : ""));
        this.position = position;
    }

    public CpqPathParseException(String message) {
        this(message, -1);
    }

    public CpqPathParseException(String message, Throwable cause) {
        super(message, cause);
        this.position = -1;
    }

    public CpqPathParseException(String message, int position, Throwable cause) {
        super(message + (position >= 0 ? " (position " + position + ")" : ""), cause);
        this.position = position;
    }

    /** 解析失败的大致字符位置（-1 表示未知） */
    public int getPosition() {
        return position;
    }
}
