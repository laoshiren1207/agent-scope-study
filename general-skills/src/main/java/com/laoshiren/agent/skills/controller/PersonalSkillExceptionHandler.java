package com.laoshiren.agent.skills.controller;

import com.laoshiren.agent.skills.exception.SkillNotFoundException;
import com.laoshiren.agent.skills.exception.SkillRevisionConflictException;
import com.laoshiren.agent.skills.exception.SkillAgentCallException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 为个人技能接口返回统一的 ProblemDetail 错误，不暴露数据库异常内容。 */
@RestControllerAdvice(assignableTypes = {PersonalSkillController.class, PersonalSkillImportController.class,
        SkillAgentController.class})
public class PersonalSkillExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(SkillAgentCallException.class)
    ProblemDetail agentCallFailed(SkillAgentCallException exception) {
        var problem = ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getMessage());
        problem.setProperty("call", exception.getCall());
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception,
                ProblemDetail.forStatusAndDetail(status, "上传文件不得超过 1 MiB"), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct().sorted().collect(Collectors.joining("；"));
        return handleExceptionInternal(exception,
                ProblemDetail.forStatusAndDetail(status, detail), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception,
                ProblemDetail.forStatusAndDetail(status, "请求体必须为合法 JSON，且字段类型正确"),
                headers, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidContent(ConstraintViolationException exception) {
        String detail = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .distinct().sorted().collect(Collectors.joining("；"));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidArgument(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicateName() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "当前租户、用户下已存在同名技能");
    }

    @ExceptionHandler(SkillNotFoundException.class)
    ProblemDetail notFound(SkillNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(SkillRevisionConflictException.class)
    ProblemDetail revisionConflict(SkillRevisionConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
