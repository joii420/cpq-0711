package com.cpq.system.lock.dto;

import java.util.List;
import java.util.UUID;

public class AcquireLocksResult {

    public List<UUID> lockIds;
    public String granularity;
    public int lockedCount;
}
