package com.loggingx.biz.notify;

import java.util.List;

/**
 * Sends a templated SMS through a cloud provider. The template is reviewed and approved by the provider in advance;
 * alerts fill its variables (see {@link SmsParams}).
 */
public interface SmsSender {

    /**
     * @param phones mainland China numbers such as {@code 13800138000}, or international numbers with {@code +}
     * @throws SmsException when the provider refuses or cannot be reached
     */
    void send(List<String> phones, SmsParams params);

    /**
     * The alert values an SMS template can use, each kept short (providers limit variable length).
     *
     * @param level  e.g. 严重 / 警告
     * @param rule   the rule name
     * @param target the host or log store
     * @param status 告警 / 恢复 / 提醒 / 测试
     * @param detail e.g. CPU 95.2% / 5 分钟 32 条
     */
    record SmsParams(String level, String rule, String target, String status, String detail) {
    }

    class SmsException extends RuntimeException {
        public SmsException(String message) {
            super(message);
        }
    }

    /**
     * @return the number as the provider expects: {@code +86...} kept or added for mainland numbers when
     * {@code e164}, otherwise the bare 11 digits for mainland numbers
     */
    static String normalize(String phone, boolean e164) {
        String digits = phone.replaceAll("[\\s()-]", "");
        String mainland = null;
        if (digits.matches("1\\d{10}")) {
            mainland = digits;
        } else if (digits.matches("\\+?86(1\\d{10})")) {
            mainland = digits.replaceFirst("^\\+?86", "");
        }
        if (mainland != null) {
            return e164 ? "+86" + mainland : mainland;
        }
        return e164 ? (digits.startsWith("+") ? digits : "+" + digits) : digits.replaceFirst("^\\+", "");
    }

    /**
     * @return the text cut to {@code max} characters (providers reject longer template variables)
     */
    static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
