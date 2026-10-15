package com.example.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * OPTIONAL — OFF by default (the bean does not even exist unless enabled).
 *
 * Checks for new RFI email replies while nobody has the app open, so the push
 * notification still reaches the phone. Enable with:
 *     APP_RFI_AUTOCHECK_ENABLED=true            (env var on Render)
 *     app.rfi.autocheck.interval-ms=600000      (default 10 min)
 *
 * Cost: one small DB query per run + one Gmail call per sender who has open RFIs.
 * Needs the Render instance to be awake when it fires (a sleeping free instance cannot
 * run it). It reuses RfiReplyAutoCheckService, so it shares the same throttle.
 */
@Component
@ConditionalOnProperty(name = "app.rfi.autocheck.enabled", havingValue = "true")
@Slf4j
public class RfiReplyBackgroundChecker {

    @Autowired
    private RfiReplyAutoCheckService autoCheck;

    @Scheduled(fixedDelayString = "${app.rfi.autocheck.interval-ms:600000}",
               initialDelayString = "${app.rfi.autocheck.interval-ms:600000}")
    public void run() {
        try {
            for (String sender : autoCheck.sendersToCheck()) {
                autoCheck.checkUser(sender);
            }
        } catch (Exception e) {
            log.warn("Background RFI reply check failed: {}", e.getMessage());
        }
    }
}