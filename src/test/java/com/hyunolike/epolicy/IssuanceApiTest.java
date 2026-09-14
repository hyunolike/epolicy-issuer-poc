package com.hyunolike.epolicy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 발급 · 검증 API. */
@AutoConfigureMockMvc
class IssuanceApiTest extends IssuanceTestBase {

    private static final String CONTRACT = "KB-2026-0001-0042";

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void seedContract() {
        contractRepository.save(TestFixtures.contractEntity());
    }

    @Test
    @DisplayName("발급 응답은 재사용 여부를 드러낸다")
    void exposesReuseFlag() throws Exception {
        mockMvc.perform(post("/api/policies/{contractNo}/issue", CONTRACT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.issueSequence").value(1))
                .andExpect(jsonPath("$.reusedExisting").value(false))
                .andExpect(jsonPath("$.contentHash").isNotEmpty())
                .andExpect(jsonPath("$.fileHash").isNotEmpty());

        // 두 번째 호출이 200 만 돌려주면 호출자는 새 파일이 생겼는지 알 수 없다.
        mockMvc.perform(post("/api/policies/{contractNo}/issue", CONTRACT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reusedExisting").value(true))
                .andExpect(jsonPath("$.issueSequence").value(1));
    }

    @Test
    @DisplayName("증권 다운로드는 PDF 와 해시 헤더를 함께 준다")
    void downloadsPdfWithHashHeaders() throws Exception {
        mockMvc.perform(post("/api/policies/{contractNo}/issue", CONTRACT)).andExpect(status().isOk());

        byte[] body = mockMvc.perform(get("/api/policies/{contractNo}/document", CONTRACT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + CONTRACT + "-r1.pdf\""))
                .andExpect(header().exists("X-File-Hash"))
                .andReturn().getResponse().getContentAsByteArray();

        org.assertj.core.api.Assertions.assertThat(new String(body, 0, 5))
                .as("PDF 헤더로 시작해야 한다")
                .isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("검증 API 가 보관 무결성과 서명 무결성을 함께 돌려준다")
    void reportsVerification() throws Exception {
        mockMvc.perform(post("/api/policies/{contractNo}/issue", CONTRACT)).andExpect(status().isOk());

        mockMvc.perform(get("/api/verification/policies/{contractNo}", CONTRACT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trustworthy").value(true))
                .andExpect(jsonPath("$.fileHashMatches").value(true))
                .andExpect(jsonPath("$.signatures[0].valid").value(true))
                .andExpect(jsonPath("$.signatures[0].coversWholeDocument").value(true));
    }

    @Test
    @DisplayName("없는 증권번호는 404, 형식이 틀린 번호는 400")
    void handlesBadInput() throws Exception {
        mockMvc.perform(get("/api/policies/{contractNo}/document", "KB-2026-9999-9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(post("/api/policies/{contractNo}/issue", "not-a-contract-no"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
