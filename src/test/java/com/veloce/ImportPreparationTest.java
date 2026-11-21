package com.veloce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.roadmap.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ImportPreparationTest {
    private final Json json=new Json(new ObjectMapper());
    private final Roadmap roadmap=new Roadmap(json);
    private final ImportParser parser=new ImportParser(json,roadmap);
    private final ObjectNode profile=json.object().put("industry","Technology").put("targetRole","Database engineer");
    ImportPreparationTest() throws Exception {}

    @Test void oneCellWithSeveralLinksIsPreparedWithoutChangingTopicsOrHours() throws Exception {
        var raw=parser.analyze("csv",("Title,Minutes,Outcome,Resource URL\n"+
            "Polyglot capstone,180,Demonstrate a working database solution,https://www.postgresql.org/docs/current/; https://www.mongodb.com/docs/manual/\n")
            .getBytes(StandardCharsets.UTF_8),profile);
        assertThat(raw.issues()).hasSize(1);
        var prepared=parser.prepare(raw,profile);
        assertThat(prepared.issues()).isEmpty();
        assertThat(prepared.warnings()).isEmpty();
        var node=roadmap.confirmed(prepared.candidate()).nodes().getFirst();
        assertThat(node.title()).isEqualTo("Polyglot capstone");
        assertThat(node.estimatedMinutes()).isEqualTo(180);
        assertThat(node.resources()).extracting(Roadmap.Resource::url).containsExactly(
            "https://www.postgresql.org/docs/current/","https://www.mongodb.com/docs/manual/");
        assertThat(prepared.preparationNotes()).anyMatch(n->n.contains("Resource links"));
        assertThat(parser.prepare(prepared,profile)).isEqualTo(prepared);
        assertThat(raw.candidate().path("nodes").get(0).path("resources")).hasSize(1);
    }

    @Test void optionalResourcesAreDeduplicatedAndUnusableLinksDoNotBlockLearning() throws Exception {
        var raw=parser.analyze("json",new ClassPathResource("fixtures/sample-product-designer-roadmap.json").getContentAsByteArray(),profile);
        var candidate=raw.candidate().deepCopy();
        var resources=((ObjectNode)candidate.path("nodes").get(1)).putArray("resources");
        resources.addObject().put("title","").put("url","[Guide](https://example.test/guide_(intro));https://example.test/reference");
        resources.addObject().put("title","Duplicate").put("url","https://example.test/reference");
        resources.addObject().put("url","www.example.test/learning");
        resources.addObject().put("url","javascript:alert(1)");
        resources.addObject().put("url","https://");
        var prepared=parser.prepare(parser.analyze("json",json.write(candidate).getBytes(StandardCharsets.UTF_8),profile),profile);
        assertThat(prepared.issues()).isEmpty();
        assertThat(roadmap.confirmed(prepared.candidate()).nodes().get(1).resources()).extracting(Roadmap.Resource::url)
            .containsExactly("https://example.test/guide_(intro)","https://example.test/reference","https://www.example.test/learning");
    }

    @Test void defaultsAreEditableAndExistingEffortAlwaysTakesPrecedence() throws Exception {
        var raw=parser.analyze("csv",("Title,Kind,Minutes,Outcome\n"+
            "Known activity,LESSON,180,Validate it\n"+
            "New practice,PRACTICE,,\n"+
            "Portfolio build,PROJECT,0,Deliver a tested project\n").getBytes(StandardCharsets.UTF_8),profile);
        var prepared=parser.prepare(raw,profile);
        assertThat(prepared.issues()).isEmpty();
        assertThat(prepared.warnings()).isEmpty();
        assertThat(roadmap.confirmed(prepared.candidate()).nodes()).extracting(Roadmap.Node::estimatedMinutes)
            .containsExactly(180,25,120);
        assertThat(prepared.assignedEstimates()).hasSize(2);
        assertThat(prepared.candidate().path("nodes").get(1).path("outcome").asText()).contains("New practice");
        assertThat(parser.prepare(prepared,profile)).isEqualTo(prepared);
    }

    @Test void cleanupDoesNotAcceptCircularPrerequisitesOrMissingTopicTitles() throws Exception {
        var raw=parser.analyze("json",new ClassPathResource("fixtures/sample-product-designer-roadmap.json").getContentAsByteArray(),profile);
        ((ObjectNode)raw.candidate().path("nodes").get(1)).putArray("dependsOn").add("interview-practice");
        var prepared=parser.prepare(raw,profile);
        assertThat(prepared.issues()).anyMatch(i->i.message().contains("cycle"));
        assertThatThrownBy(()->roadmap.confirmed(prepared.candidate())).isInstanceOf(Problem.class);
        ((ObjectNode)raw.candidate().path("nodes").get(1)).put("title","");
        assertThat(parser.prepare(raw,profile).issues()).isNotEmpty();
    }
}
