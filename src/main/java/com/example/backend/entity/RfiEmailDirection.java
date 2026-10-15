package com.example.backend.entity;

/**
 * Tracks the direction of emails in an RFI conversation.
 * OUTGOING = email sent from our system to the recipient
 * INCOMING = email received from the recipient
 */
public enum RfiEmailDirection {
    OUTGOING,
    INCOMING
}