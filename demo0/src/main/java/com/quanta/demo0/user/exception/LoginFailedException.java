package com.quanta.demo0.user.exception;
import com.quanta.demo0.platform.common.exception.BaseException;


public class LoginFailedException extends BaseException{
    public LoginFailedException(String msg) {
        super(msg);
    }
}
