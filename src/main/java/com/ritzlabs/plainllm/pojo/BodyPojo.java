package com.ritzlabs.plainllm.pojo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BodyPojo {

    private String model;

    private boolean stream;

    private List<Message> messages;
}
