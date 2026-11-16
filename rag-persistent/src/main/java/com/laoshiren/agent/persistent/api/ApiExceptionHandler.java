package com.laoshiren.agent.persistent.api;

import com.laoshiren.agent.persistent.service.ModelCallException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidUpload(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail uploadTooLarge() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "上传文件不得超过 1 MiB");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicateVersion() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "该文档版本及向量配置的片段已存在，请使用新的 documentVersion");
    }

    @ExceptionHandler(ModelCallException.class)
    ProblemDetail modelFailure(ModelCallException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail stateConflict(IllegalStateException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
