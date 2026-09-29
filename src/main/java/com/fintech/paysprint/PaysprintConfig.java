package com.fintech.paysprint;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaysprintProperties.class)
public class PaysprintConfig {}
