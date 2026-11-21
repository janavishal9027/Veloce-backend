package com.veloce.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloce.common.Json;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GoogleCalendarTest {
    @Test void eventIdsAreStableAndIsolatedByOwner() {
        UUID owner=UUID.randomUUID(),slot=UUID.randomUUID();
        assertThat(GoogleCalendarService.eventId(owner,slot)).matches("[a-v0-9]{5,1024}").isEqualTo(GoogleCalendarService.eventId(owner,slot))
            .isNotEqualTo(GoogleCalendarService.eventId(UUID.randomUUID(),slot));
    }
    @Test void eventKeepsTimezoneAndAppliesFirstSessionRemindersAndPrivateTitles() {
        var json=new Json(new ObjectMapper());var slot=new HashMap<String,Object>();
        slot.put("id",UUID.randomUUID());slot.put("kind","STUDY");slot.put("title","SQL foundations");slot.put("name","Test workspace");
        slot.put("outcome","Private concept notes");slot.put("node_kind","LESSON");slot.put("zone","Asia/Kolkata");
        slot.put("starts_at",OffsetDateTime.parse("2026-11-18T12:30:00Z"));slot.put("ends_at",OffsetDateTime.parse("2026-11-18T14:30:00Z"));
        slot.put("rules","{\"channel\":\"CALENDAR\",\"rules\":[{\"scope\":\"FIRST\",\"offset\":10},{\"scope\":\"ALL\",\"offset\":5}],\"genericTitles\":true}");
        var event=GoogleCalendarService.event(json,slot,true);
        assertThat(event.path("summary").asText()).isEqualTo("Veloce study");assertThat(event.toString()).doesNotContain("Private concept notes");
        assertThat(event.path("start").path("timeZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(event.path("reminders").path("overrides").size()).isEqualTo(2);
        assertThat(GoogleCalendarService.event(json,slot,false).path("reminders").path("overrides").size()).isEqualTo(1);
        slot.put("kind","BREAK");assertThat(GoogleCalendarService.event(json,slot,false).path("reminders").path("overrides").isEmpty()).isTrue();
    }
}
