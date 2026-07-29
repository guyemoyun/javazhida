package com.zhida.controller;

import com.zhida.common.Result;
import com.zhida.dto.ConversationSnapshot;
import com.zhida.exception.ApiException;
import com.zhida.store.LocalConversationStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final LocalConversationStore store;

    public ConversationController(LocalConversationStore store) {
        this.store = store;
    }

    @GetMapping
    public Result<ConversationSnapshot> load() {
        return Result.ok(store.load());
    }

    @PutMapping
    public Result<Void> replace(@Valid @RequestBody ConversationSnapshot snapshot) {
        if (snapshot.version() != ConversationSnapshot.CURRENT_VERSION) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的会话数据版本");
        }
        store.replace(snapshot);
        return Result.ok();
    }
}
