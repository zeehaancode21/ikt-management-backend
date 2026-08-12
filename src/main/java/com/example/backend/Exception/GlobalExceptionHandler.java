package com.example.backend.Exception;

import com.example.backend.entity.ApiResponse;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error("Method not supported: " + ex.getMessage()));
    }

    // MUST come before the generic RuntimeException handler below —
    // ResponseStatusException IS a RuntimeException, and without this
    // more specific handler every throw new ResponseStatusException(FORBIDDEN, ...)
    // etc. across the whole app was being caught by handleRuntimeException()
    // and rewritten to a plain 400, discarding the real status code.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ex.getReason()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // The client disconnected (browser navigated away / cancelled a request,
    // most often mid-download of a streamed image). The connection is gone,
    // so there's no one to send a response to — don't try. This is the
    // ClientAbortException itself (thrown directly by Tomcat).
    @ExceptionHandler(ClientAbortException.class)
    @ResponseStatus(HttpStatus.OK) // Don't return error for client disconnects
    public void handleClientAbort(ClientAbortException e) {
        log.warn("Client disconnected during download: {}", e.getMessage());
        // Don't try to send a response - the client is already gone
    }

    // Spring wraps a broken-pipe/client-abort that happens while writing an
    // async/streamed response (e.g. an in-progress image download) as
    // AsyncRequestNotUsableException rather than ClientAbortException, so it
    // needs its own handler for the same reason as above: the client is gone
    // and the response's Content-Type (e.g. image/png) is already committed,
    // so attempting to write a JSON ApiResponse body here would itself fail
    // with "No converter for ApiResponse with preset Content-Type ...".
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    @ResponseStatus(HttpStatus.OK)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.warn("Client disconnected during async response write: {}", e.getMessage());
        // Don't try to send a response - the client is already gone
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex, HttpServletResponse response) {
        // If the response is already committed (e.g. we were mid-stream on
        // an image/file download and the connection dropped, or some other
        // filter already wrote headers/bytes), there's nothing left to send
        // a JSON error body into — trying to do so is what causes the
        // "No converter for ApiResponse with preset Content-Type ..." error.
        if (response.isCommitted()) {
            log.warn("Exception occurred after response was already committed, cannot write error body", ex);
            return null;
        }

        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error("Internal server error"));
    }
}