package com.zhida.controller;

import com.zhida.dto.ConversationSnapshot;
import com.zhida.store.LocalConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationController.class)
class ConversationControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocalConversationStore store;

    @Test
    void loadReturnsVersionedSnapshot() throws Exception {
        when(store.load()).thenReturn(new ConversationSnapshot(List.of()));

        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.conversations").isArray());
    }

    @Test
    void replaceRejectsUnknownSnapshotVersion() throws Exception {
        mockMvc.perform(put("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":99,\"conversations\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("不支持的会话数据版本"));
    }
}
