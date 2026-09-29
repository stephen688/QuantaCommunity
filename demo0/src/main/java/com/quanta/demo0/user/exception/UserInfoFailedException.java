package com.quanta.demo0.user.exception;
import com.quanta.demo0.platform.common.exception.BaseException;


public class UserInfoFailedException extends BaseException {
    public UserInfoFailedException(String message) {
        super(message);
    }
}
