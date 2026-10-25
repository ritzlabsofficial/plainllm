package com.ritzlabs.plainllm.controller;

import com.ritzlabs.plainllm.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;


    @PostMapping("/single-response")
    public String llmSingleResponse(@RequestParam(name = "query", required = true) String query) throws InterruptedException {

        log.info("single-response request received with query: {}", query);

        return chatService.singleResponse(query);
    }

    @PostMapping(value = "/streaming-response", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter llmStreamingResponse (@RequestParam(name = "query", required = true) String query) {

        log.info("streaming-response request received with query: {}", query);

        SseEmitter emitter = new SseEmitter(1800_000L);

        chatService.streamLlmResponse(query, emitter);

        return emitter;
    }

}
