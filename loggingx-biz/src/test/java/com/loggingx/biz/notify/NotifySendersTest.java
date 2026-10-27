package com.loggingx.biz.notify;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifySendersTest {

    /**
     * The signature example of the Alibaba Cloud ECS API documentation ("Request signatures", DescribeRegions).
     */
    @Test
    void signsLikeTheAlibabaCloudDocumentation() {
        Map<String, String> query = new TreeMap<>(Map.of(
                "AccessKeyId", "testid",
                "Action", "DescribeRegions",
                "Format", "XML",
                "SignatureMethod", "HMAC-SHA1",
                "SignatureNonce", "3ee8c1b8-83d3-44af-a94f-4e0ad82fd6cf",
                "SignatureVersion", "1.0",
                "Timestamp", "2016-02-23T12:46:24Z",
                "Version", "2014-05-26"));
        assertEquals("OLeaidS1JvxuMvnyHOwuJ+uX5qY=", AliyunSmsSender.signature(query, "testsecret"));
        assertEquals("a%20b%2A~%2F", AliyunSmsSender.encode("a b*~/"));
    }

    /**
     * The example of the Tencent Cloud API documentation ("Signature v3", CVM DescribeInstances).
     */
    @Test
    void signsLikeTheTencentCloudDocumentation() {
        // The documentation gives the request body's SHA-256 and the resulting string to sign; its final signature
        // uses a secret key that is masked in the documentation, so it cannot be reproduced.
        assertEquals("TC3-HMAC-SHA256\n1551113065\n2019-02-25/cvm/tc3_request\n"
                        + "2815843035062fffda5fd6f2a44ea8a34818b0dc46f024b8b3786976a3adda7a",
                TencentSmsSender.stringToSign("cvm", "cvm.tencentcloudapi.com", null,
                        "99d58dfbc6745f6747f36bfca17dee5e6881dc0428a0a36f96199342bc5b4907", 1551113065L));
        String authorization = TencentSmsSender.signed("sms", "sms.tencentcloudapi.com", "SendSms",
                "99d58dfbc6745f6747f36bfca17dee5e6881dc0428a0a36f96199342bc5b4907", 1551113065L, "AKIDEXAMPLE",
                "secret");
        assertTrue(authorization.startsWith("TC3-HMAC-SHA256 Credential=AKIDEXAMPLE/2019-02-25/sms/tc3_request, "
                + "SignedHeaders=content-type;host;x-tc-action, Signature="), authorization);
        assertEquals(64, authorization.substring(authorization.indexOf("Signature=") + 10).length());
    }

    @Test
    void normalizesPhoneNumbersPerProvider() {
        assertEquals("+8613800138000", SmsSender.normalize("138 0013 8000", true));
        assertEquals("13800138000", SmsSender.normalize("+86 13800138000", false));
        assertEquals("+85291234567", SmsSender.normalize("85291234567", true));
        assertEquals("85291234567", SmsSender.normalize("+85291234567", false));
        assertEquals("一二三四…", SmsSender.cut("一二三四五六", 5));
    }

    @Test
    void encodesNonAsciiMailHeaders() {
        assertEquals("CPU alert", SmtpClient.encodeWord("CPU alert"));
        assertEquals("=?UTF-8?B?5ZGK6K2m?=", SmtpClient.encodeWord("告警"));
    }
}
