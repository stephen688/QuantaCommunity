package com.quanta.demo0.platform.security.exception;
import com.quanta.demo0.platform.common.exception.BaseException;


public class AuthFailedException extends BaseException {
    public AuthFailedException(String message) {
        super(message);
    }
}
