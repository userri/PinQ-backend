package com.example.pinq_backend.user.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.pinq_backend.auth.service.JwtTokenProvider;
import com.example.pinq_backend.quiz.exception.GlobalExceptionHandler;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.user.domain.User;
import com.example.pinq_backend.user.dto.BookmarkToggleResponse;
import com.example.pinq_backend.user.service.BookmarkService;
import com.example.pinq_backend.user.service.UserService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BookmarkController HTTP 계층 검증.
 *
 *  1. GET  /api/bookmarks            : 목록 조회 200
 *  2. POST /api/bookmarks/{quizId}   : 추가 idempotent 200
 *  3. DELETE /api/bookmarks/{quizId} : 해제 idempotent 200
 *  4. POST 존재하지 않는 quizId     : 404
 */
@WebMvcTest(BookmarkController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
@WithMockUser
class BookmarkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookmarkService bookmarkService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        User demoUser = mock(User.class);
        given(demoUser.getId()).willReturn(1L);
        given(userService.findDemoUser()).willReturn(demoUser);
    }

    @Test
    @DisplayName("GET /api/bookmarks 는 200 과 목록을 반환한다")
    void getBookmarks_returnsOk() throws Exception {
        given(bookmarkService.getBookmarks(anyLong())).willReturn(List.of());

        mockMvc.perform(get("/api/bookmarks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /api/bookmarks/{quizId} 는 200 과 bookmarked=true 를 반환한다")
    void addBookmark_returnsBookmarked() throws Exception {
        given(bookmarkService.addBookmark(anyLong(), anyLong()))
            .willReturn(new BookmarkToggleResponse(1L, true));

        mockMvc.perform(post("/api/bookmarks/1").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quizId").value(1))
            .andExpect(jsonPath("$.bookmarked").value(true));
    }

    @Test
    @DisplayName("DELETE /api/bookmarks/{quizId} 는 200 과 bookmarked=false 를 반환한다")
    void removeBookmark_returnsUnbookmarked() throws Exception {
        given(bookmarkService.removeBookmark(anyLong(), anyLong()))
            .willReturn(new BookmarkToggleResponse(1L, false));

        mockMvc.perform(delete("/api/bookmarks/1").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quizId").value(1))
            .andExpect(jsonPath("$.bookmarked").value(false));
    }

    @Test
    @DisplayName("존재하지 않는 quizId 북마크 추가 시 404 를 반환한다")
    void addBookmark_quizNotFound_returns404() throws Exception {
        given(bookmarkService.addBookmark(anyLong(), anyLong()))
            .willThrow(new QuizNotFoundException(999L));

        mockMvc.perform(post("/api/bookmarks/999").with(csrf()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }
}
