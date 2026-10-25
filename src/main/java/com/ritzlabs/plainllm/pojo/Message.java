package com.ritzlabs.plainllm.pojo;

import lombok.Data;

@Data
public class Message {

    private String role;

    private String content;
}