package com.fintech.bills;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KbillProperties.class)
public class KbillConfig {}
