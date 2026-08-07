package pl.dolien.climbcheck.stats;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StatsService statsService;

    @Test
    void getStats_shouldReturn200WithLiveCounts() throws Exception {
        when(statsService.getStats()).thenReturn(new StatsResponse(12, 34, 567));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboards").value(12))
                .andExpect(jsonPath("$.players").value(34))
                .andExpect(jsonPath("$.lpPoints").value(567));
    }

    @Test
    void getStats_shouldReturn200WithZerosForEmptyDatabase() throws Exception {
        when(statsService.getStats()).thenReturn(new StatsResponse(0, 0, 0));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboards").value(0))
                .andExpect(jsonPath("$.players").value(0))
                .andExpect(jsonPath("$.lpPoints").value(0));
    }
}
