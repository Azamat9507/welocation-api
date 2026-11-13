package com.minsu.welocation.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail badRequest(BadRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail unauthorized(UnauthorizedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail forbidden(ForbiddenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ProblemDetail> tooManyRequests(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ProblemDetail unavailable(ServiceUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    // an admin photo over spring.servlet.multipart.max-file-size
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail tooLarge() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "photo: larger than 5 MB");
    }

    // Jackson's message quotes the offending input and Java types; none of that goes back to the client
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "malformed request body");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining(", "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getName() + ": invalid value");
    }

    // conversion failures carry the Java type name in their default message; keep it out of responses
    private static String describe(FieldError err) {
        return err.getField() + ": " + (err.isBindingFailure() ? "invalid value" : err.getDefaultMessage());
    }
}
