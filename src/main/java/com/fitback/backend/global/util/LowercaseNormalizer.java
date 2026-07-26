package com.fitback.backend.global.util;

import java.util.Locale;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LowercaseNormalizer {

    //이메일처럼 대소문자를 구분하지 않는 값의 비교 기준 통일
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
