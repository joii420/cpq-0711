package com.cpq.partno;

/**
 * PartNoProvider 申请 hf_part_no 失败时抛出.
 *
 * <p>常见原因:
 * <ul>
 *   <li>本地实现:序列表行锁冲突无法获取 / DB 不可用</li>
 *   <li>外部 API 实现:网络超时 / 5xx / 返回格式不符</li>
 * </ul>
 */
public class PartNoProvisionException extends RuntimeException {

    public PartNoProvisionException(String message) {
        super(message);
    }

    public PartNoProvisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
