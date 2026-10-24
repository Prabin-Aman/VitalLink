package com.vitallink.api.notifications;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.notifications.NotificationService.Notification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me/notifications")
@Tag(name = "notifications")
public class NotificationController {

    /** Mark some ({@code ids}) or every ({@code all: true}) notification read. */
    public record ReadIn(@Size(max = 200) List<UUID> ids, Boolean all) {
        public ReadIn {
            ids = ids == null ? List.of() : ids;
            all = all != null && all;
        }
    }

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "The feed, newest first.")
    public List<Notification> list(
            CurrentUser me,
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.list(me.id(), me.user().timezone(), unread, limit);
    }

    @PostMapping("/read")
    public Map<String, Integer> markRead(CurrentUser me, @Valid @RequestBody ReadIn body) {
        return Map.of("updated", service.markRead(me.id(), body.ids(), body.all()));
    }
}
