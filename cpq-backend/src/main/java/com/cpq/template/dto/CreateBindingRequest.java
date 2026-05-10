package com.cpq.template.dto;

import java.util.List;
import java.util.UUID;

public class CreateBindingRequest {

    public UUID productId;
    public List<String> processIds;
    public UUID templateId;
    public Boolean isDefault = false;
}
