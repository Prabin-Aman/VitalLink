package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Notifications, the health library, and the family view. */
class FeedLibraryFamilyTest extends ApiTest {

    static String email(String tag) {
        return tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    Res lab(String u) {
        return post(
                u,
                "/v1/me/labs",
                Map.of(
                        "name",
                        "Lipid Profile",
                        "short_name",
                        "Lipids",
                        "facility",
                        "Trinity Valley Medical Center",
                        "collected_at",
                        OffsetDateTime.now().minusDays(1).toString(),
                        "results",
                        List.of(
                                Map.of("name", "LDL", "value", 162, "unit", "mg/dL", "low", 0, "high", 100),
                                Map.of("name", "HDL", "value", 55, "unit", "mg/dL", "low", 40, "high", 90))));
    }

    // ---------------------------------------------------------------- notifications

    @Test
    void newLabResultIsInTheFeedAndCanBeRead() {
        String u = user();
        assertThat(lab(u).status()).isEqualTo(201);
        Res feed = get(u, "/v1/me/notifications");
        assertThat(feed.body().size()).isEqualTo(1);
        assertThat(feed.at("/0/kind").asString()).isEqualTo("health");
        assertThat(feed.at("/0/title").asString()).isEqualTo("New lab result available");
        assertThat(feed.at("/0/body").asString())
                .isEqualTo("Lipid Profile from Trinity Valley Medical Center. 1 value outside range.");
        assertThat(feed.at("/0/read").asBoolean()).isFalse();
        String id = feed.at("/0/id").asString();

        // Someone else can't mark it read.
        assertThat(post(user(), "/v1/me/notifications/read", Map.of("ids", List.of(id)))
                        .at("/updated")
                        .asInt())
                .isZero();
        assertThat(post(u, "/v1/me/notifications/read", Map.of("ids", List.of(id)))
                        .at("/updated")
                        .asInt())
                .isEqualTo(1);
        assertThat(get(u, "/v1/me/notifications?unread=true").body().size()).isZero();
        lab(u);
        assertThat(post(u, "/v1/me/notifications/read", Map.of("all", true))
                        .at("/updated")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void ownersHearWhenSharedRecordsAreOpened() {
        String ownerEmail = email("owner");
        String owner = tokenFor(ownerEmail);
        String ownerId = get(owner, "/v1/me").at("/id").asString();
        String viewerEmail = email("doc");
        String viewer = tokenFor(viewerEmail);
        get(viewer, "/v1/me");
        post(
                owner,
                "/v1/me/shares",
                Map.of(
                        "email",
                        viewerEmail,
                        "label",
                        "Dr. Marcus Hale",
                        "kind",
                        "doctor",
                        "scopes",
                        List.of("records")));
        get(viewer, "/v1/users/" + ownerId + "/records");
        get(viewer, "/v1/users/" + ownerId + "/records"); // twice in a day: one notification
        Res feed = get(owner, "/v1/me/notifications");
        assertThat(feed.body().size()).isEqualTo(1);
        assertThat(feed.at("/0/kind").asString()).isEqualTo("records");
        assertThat(feed.at("/0/body").asString()).isEqualTo("Dr. Marcus Hale viewed your shared records.");
    }

    // ---------------------------------------------------------------- library

    @Test
    void libraryIsLoadedFromResources() {
        Res l = get(user(), "/v1/library");
        assertThat(l.status()).isEqualTo(200);
        List<String> categories = new ArrayList<>();
        l.at("/categories").forEach(c -> categories.add(c.asString()));
        assertThat(categories)
                .containsExactly("Fitness", "Nutrition", "Sleep", "Heart", "Diabetes", "Medication", "First Aid");
        assertThat(l.at("/featured/slug").asString()).isEqualTo("how-much-sleep-do-adults-need");
        assertThat(l.at("/featured_mobile/slug").asString()).isEqualTo("understanding-blood-pressure-numbers");
        assertThat(l.at("/trending").size()).isEqualTo(13);
        assertThat(l.at("/trending/0/category").asString()).isEqualTo("Sleep");
        // Decision D14: no reviewer is named unless a real person agreed.
        l.at("/trending").forEach(a -> assertThat(a.get("reviewer").isNull()).isTrue());

        Res article = get(user(), "/v1/library/articles/hydration-basics");
        assertThat(article.at("/title").asString()).isEqualTo("Hydration basics");
        assertThat(article.at("/body_md").asString()).contains("not medical advice");
        assertThat(get(user(), "/v1/library/articles/who-can-donate").status()).isEqualTo(404);
        assertThat(get(user(), "/v1/library/categories/first_aid").body().size())
                .isEqualTo(2);
        assertThat(get(user(), "/v1/library/categories/blood").status()).isEqualTo(404);
        assertThat(get(null, "/v1/library").status()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- family

    @Test
    void familyShowsWhatEachShareCovers() {
        String ownerEmail = email("mum");
        String owner = token(ownerEmail, null, true, "Susan", "Carter");
        String ownerId = get(owner, "/v1/me").at("/id").asString();
        patch(owner, "/v1/me/account", Map.of("date_of_birth", "1958-03-02"));
        post(owner, "/v1/me/conditions", Map.of("label", "Type 2 diabetes", "kind", "chronic"));
        post(owner, "/v1/me/conditions", Map.of("label", "Penicillin", "kind", "allergy"));
        post(
                owner,
                "/v1/me/medications",
                Map.of("name", "Metformin", "form", "tablet", "strength", "500 mg", "times", List.of("08:00")));

        String viewerEmail = email("son");
        String viewer = token(viewerEmail, null, true, "Alex", "Carter");
        get(viewer, "/v1/me");
        post(
                owner,
                "/v1/me/shares",
                Map.of(
                        "email",
                        viewerEmail,
                        "label",
                        "Alex",
                        "kind",
                        "family",
                        "scopes",
                        List.of("records", "activity")));

        Res family = get(viewer, "/v1/me/family");
        assertThat(family.body().size()).isEqualTo(2);
        assertThat(family.at("/0/relation").asString()).isEqualTo("You");
        assertThat(family.at("/0/access").isNull()).isTrue();
        JsonNode mum = family.at("/1");
        assertThat(mum.get("id").asString()).isEqualTo(ownerId);
        assertThat(mum.get("name").asString()).isEqualTo("Susan Carter");
        assertThat(mum.get("relation").asString()).isEqualTo("Family");
        assertThat(mum.get("age").asInt()).isGreaterThanOrEqualTo(68);
        assertThat(mum.at("/access/level").asString()).isEqualTo("partial");
        List<String> granted = new ArrayList<>();
        mum.at("/access/scopes").forEach(s -> {
            if (s.get("granted").asBoolean()) granted.add(s.get("short_label").asString());
        });
        assertThat(granted).containsExactly("Activity", "Records and medications");
        assertThat(mum.at("/summary/resting_hr").isNull()).isTrue(); // vitals not shared
        assertThat(mum.at("/summary/medications").asInt()).isEqualTo(1);
        assertThat(mum.at("/summary/conditions").size()).isEqualTo(2);
        assertThat(mum.at("/summary/conditions/1/label").asString()).isEqualTo("Penicillin allergy");

        // The summary read was audited in her access log.
        Res log = get(owner, "/v1/me/access-log");
        assertThat(log.at("/0/accessed").asString()).isEqualTo("Records");

        assertThat(get(viewer, "/v1/users/" + ownerId + "/summary")
                        .at("/medications")
                        .asInt())
                .isEqualTo(1);
        assertThat(get(user(), "/v1/users/" + ownerId + "/summary").status()).isEqualTo(404);
    }
}
