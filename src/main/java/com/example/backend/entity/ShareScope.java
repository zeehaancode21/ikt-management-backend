package com.example.backend.entity;

/**
 * What a public share link points at.
 *
 * CLIENT  - every project of one client (optionally limited to a single year).
 * PROJECT - exactly one project (main details + its change orders).
 */
public enum ShareScope {
    CLIENT,
    PROJECT
}