package com.mirboard.domain.admin;

import org.springframework.stereotype.Service;

/**
 * D-86 — 어드민 권한 판정. 매 요청 admin_roles 조회(소규모라 비용 무시·즉시 반영).
 */
@Service
public class AdminAuthorization {

    private final AdminRoleRepository adminRoles;

    public AdminAuthorization(AdminRoleRepository adminRoles) {
        this.adminRoles = adminRoles;
    }

    public boolean isAdmin(long userId) {
        return adminRoles.existsById(userId);
    }

    public void requireAdmin(long userId) {
        if (!isAdmin(userId)) {
            throw new NotAdminException();
        }
    }
}
