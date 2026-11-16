package com.laoshiren.agent.persistent.service;

import com.laoshiren.agent.persistent.api.ChunkRequests;
import com.laoshiren.agent.persistent.config.RagProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RagAnswerServiceTest {
    @Test
    void skipsChatModelWhenNoReferencesAreRetrieved() {
        KbChunkService chunks = mock(KbChunkService.class);
        OpenAIChatModel model = mock(OpenAIChatModel.class);
        var request = new ChunkRequests.Search(1L, 2L, "有资料吗？", null, null);
        when(chunks.search(request)).thenReturn(List.of());
        ReActAgent agent = mock(ReActAgent.class);
        var answer = new RagAnswerService(chunks, model, new RagProperties(), agent).ask(request);
        assertThat(answer.answer()).isEqualTo("现有资料未说明。");
        assertThat(answer.references()).isEmpty();
        verifyNoInteractions(model, agent);
    }
}
