package com.quanta.demo0.exception;
import com.quanta.demo0.platform.common.exception.BaseException;


public class SearchFailedException extends BaseException {
    public SearchFailedException(String message) {
        super(message);
    }
}
