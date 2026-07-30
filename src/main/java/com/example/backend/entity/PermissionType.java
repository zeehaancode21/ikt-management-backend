package com.example.backend.entity;

// Category selected by the employee when requesting hours-based
// "Permission" time away. Mirrors PERMISSION_TYPE_LABELS on the frontend
// (PermissionPortal.tsx) — keep both in sync if a value is added/removed.
public enum PermissionType {
    PERSONAL,
    MEDICAL,
    // OFFICIAL,
    EMERGENCY,
    OTHER
}