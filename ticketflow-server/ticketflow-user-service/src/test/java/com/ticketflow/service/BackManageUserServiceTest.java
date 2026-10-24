package com.ticketflow.service;

import com.ticketflow.dto.BackManageLoginDto;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.properties.BackManageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台管理默认口令识别。
 * 回归重点：仓库默认配置是 admin/admin123，早期实现只识别 admin/admin，导致后台默认口令可以直接登录。
 */
class BackManageUserServiceTest {

    private BackManageUserService serviceWith(String username, String password) {
        BackManageProperties properties = new BackManageProperties();
        properties.setUsername(username);
        properties.setPassword(password);
        BackManageUserService service = new BackManageUserService();
        ReflectionTestUtils.setField(service, "backManageProperties", properties);
        return service;
    }

    private BackManageLoginDto loginWith(String username, String password) {
        BackManageLoginDto dto = new BackManageLoginDto();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    @Test
    void adminWithRepositoryDefaultPasswordShouldBeTreatedAsDefaultCredential() {
        BackManageUserService service = serviceWith("admin", "admin123");

        assertTrue(service.isDefaultCredential());
        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> service.verifyUser(loginWith("admin", "admin123")));
        assertTrue(exception.getMessage().contains("默认凭证"));
    }

    @Test
    void adminWithLegacyDefaultPasswordShouldStillBeBlocked() {
        assertThrows(TicketFlowFrameException.class,
                () -> serviceWith("admin", "admin").verifyUser(loginWith("admin", "admin")));
    }

    @Test
    void blankPasswordShouldBeTreatedAsDefaultCredential() {
        assertTrue(serviceWith("admin", "   ").isDefaultCredential());
        assertTrue(serviceWith("admin", null).isDefaultCredential());
        assertTrue(serviceWith(null, "S0me-Str0ng-Pass").isDefaultCredential());
    }

    @Test
    void strongCredentialShouldNotBeTreatedAsDefault() {
        BackManageUserService service = serviceWith("ops-admin", "Tf!9xQ2#manage");

        assertTrue(!service.isDefaultCredential());
        assertDoesNotThrow(() -> service.verifyUser(loginWith("ops-admin", "Tf!9xQ2#manage")));
    }

    @Test
    void strongPasswordButWrongInputShouldBeRejectedByComparison() {
        BackManageUserService service = serviceWith("ops-admin", "Tf!9xQ2#manage");

        TicketFlowFrameException wrongUsername = assertThrows(TicketFlowFrameException.class,
                () -> service.verifyUser(loginWith("admin", "Tf!9xQ2#manage")));
        assertTrue(wrongUsername.getMessage().contains("用户名错误"));

        TicketFlowFrameException wrongPassword = assertThrows(TicketFlowFrameException.class,
                () -> service.verifyUser(loginWith("ops-admin", "Tf!9xQ2#wrong")));
        assertTrue(wrongPassword.getMessage().contains("用户密码错误"));
    }

    @Test
    void nonDefaultUsernameKeepsWeakPasswordUsableForLocalDemo() {
        // 只有默认用户名 + 弱口令才算「默认凭证」；自定义用户名仍走正常的用户名/密码比对
        assertTrue(!serviceWith("demo-ops", "admin123").isDefaultCredential());
    }
}