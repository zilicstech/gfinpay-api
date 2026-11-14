package com.fintech.sms;

import java.util.Map;

public interface SmsSender {

    void send(String mobile, String templateKey, Map<String, String> params);
}

