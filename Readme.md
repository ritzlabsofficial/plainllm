# Lightweight Java LLM Client

A zero-framework, lightweight Java library designed to interact directly with LLM APIs over plain HTTP using the native JDK `HttpClient` and a JSON library.

This project demonstrates foundational knowledge of how LLM APIs operate under the hood, featuring clean interface design, robust error handling, streaming, and structured JSON output validation.

## Features

- **Framework-Free Core:** Uses standard JDK `HttpClient` and basic JSON parsing without reliance on heavy external AI frameworks.
- **Chat Completion & Token Usage:** Simple API to execute chat requests and retrieve parsed responses alongside detailed token usage metrics.
- **Real-Time Streaming:** Support for streaming responses printed to the console or processed as tokens arrive.
- **Structured Output & Self-Correction:** Requests structured JSON output, validates it against a target schema, and automatically retries once with feedback if validation fails.
- **Resilience & Rate-Limiting:** Handles network timeouts, HTTP rate limits, and transient server errors using exponential backoff retries.


## Error Handling & Retry Matrix

The library categorizes HTTP status codes and operational failures to apply appropriate retry behaviors:

| Error / Status Code | Category | Retried? | Behavior / Handling |
| :--- | :--- | :---: | :--- |
| **429 Rate Limit** | Rate Limiting | **Yes** | Retried using exponential backoff with jitter. |
| **500, 502, 503, 504** | Transient Server Errors | **Yes** | Retried using exponential backoff up to max attempts limit. |
| **Network Timeout** | Connection / Read Timeout | **Yes** | Retried using exponential backoff. |
| **Invalid JSON Schema** | Output Validation | **Yes (Once)** | Appends the validation error message to the conversation context and retries the prompt once. |
| **401 / 403** | Auth Failure | **No** | Throws `AuthenticationException` immediately. |
| **400 Bad Request** | Invalid Payload | **No** | Throws `InvalidRequestException` with error details. |
| **404 Not Found** | Missing Endpoint/Model | **No** | Throws `NotFoundException` immediately. |

