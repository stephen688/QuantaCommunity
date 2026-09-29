package com.quanta.demo0.content.exception;
import com.quanta.demo0.platform.common.exception.BaseException;


import com.quanta.demo0.platform.security.context.BaseContext;

public class ContentFailedException extends BaseException {
    public ContentFailedException(String message) {
        super(message);
    }
}
