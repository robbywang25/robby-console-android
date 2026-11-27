package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class EngineeringModelTest {
    @Test public void githubExternalUrlsStrictlyValidateOwnersAndScheme() {
        assertEquals("https://github.com/robbywang25/god", EngineeringModel.validatedGithubUrl("https://github.com/robbywang25/god"));
        assertEquals("https://github.com/robbyisrobby/site-admin/commit/abc", EngineeringModel.validatedGithubUrl("https://github.com/robbyisrobby/site-admin/commit/abc"));
        assertNull(EngineeringModel.validatedGithubUrl("http://github.com/robbywang25/god"));
        assertNull(EngineeringModel.validatedGithubUrl("https://github.com/someoneelse/repo"));
        assertNull(EngineeringModel.validatedGithubUrl("https://evil.example/robbywang25/god"));
        assertNull(EngineeringModel.validatedGithubUrl("https://user@github.com/robbywang25/god"));
        assertNull(EngineeringModel.validatedGithubUrl("https://github.com/robbywang25/../other"));
    }

    @Test public void filtersByRepositoryAndStripsInvalidExternalLinks() throws Exception {
        JSONObject payload = new JSONObject()
            .put("schemaVersion", 2).put("apiVersion", "2026-11-26")
            .put("repositoryIndex", new JSONArray()
                .put(new JSONObject().put("repository", "robbywang25/god").put("name", "god").put("url", "https://github.com/robbywang25/god"))
                .put(new JSONObject().put("repository", "robbywang25/app").put("name", "app").put("url", "https://evil.example/x")))
            .put("items", new JSONArray()
                .put(new JSONObject().put("id", "1").put("repository", "robbywang25/god").put("summary", "A").put("commitUrl", "https://github.com/robbywang25/god/commit/1"))
                .put(new JSONObject().put("id", "2").put("repository", "robbywang25/app").put("summary", "B").put("commitUrl", "http://github.com/robbywang25/app/commit/2")));
        EngineeringModel.Model m = EngineeringModel.parse(payload);
        assertEquals(2, m.repos.size());
        assertNotNull(m.repos.get(0).url);
        assertNull(m.repos.get(1).url);
        assertEquals(1, EngineeringModel.filterItems(m, "robbywang25/god").size());
        assertNull(m.items.get(1).commitUrl);
    }
}
