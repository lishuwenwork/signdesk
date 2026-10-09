package com.signdesk.common;

import static org.junit.jupiter.api.Assertions.*;

import com.signdesk.domain.Platform;
import com.signdesk.domain.bo.PlatformBo;
import com.signdesk.domain.vo.DeletedVo;
import com.signdesk.domain.vo.IdVo;
import com.signdesk.domain.vo.PageVo;
import com.signdesk.domain.vo.SavedVo;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;

class LayeringContractTest {
    @Test
    void jackson3BindsPrivateInputFieldsAndRetainsValidation() {
        var input =
                Json.read(
                        """
                        {"name":"平台","note":"fixture-private-note","enabled":true,"version":7}
                        """,
                        PlatformBo.class);
        assertEquals("平台", input.getName());
        assertEquals("fixture-private-note", input.getNote());
        assertTrue(input.isEnabled());
        assertEquals(7, input.getVersion());
        assertFalse(input.toString().contains("fixture-private-note"));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(input).isEmpty());
            input.setName(" ");
            assertFalse(validator.validate(input).isEmpty());
            input.setName("名".repeat(41));
            assertFalse(validator.validate(input).isEmpty());
            input.setName("平台");
            input.setNote("注".repeat(501));
            assertFalse(validator.validate(input).isEmpty());
        }
    }

    @Test
    void entityFieldsArePrivateAndDoNotGeneratePayloadToString() {
        for (var field : Platform.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
        }
        for (var field : PlatformBo.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
        }
        var entity = new Platform();
        entity.setNote("fixture-private-note");
        assertEquals("fixture-private-note", entity.getNote());
        assertFalse(entity.toString().contains("fixture-private-note"));
    }

    @Test
    void actionResponsesKeepExistingFieldsWithoutAnEnvelope() {
        assertEquals(
                Map.of("id", "9007199254740993"),
                Json.map(Json.write(new IdVo("9007199254740993"))));
        assertEquals(Map.of("saved", true), Json.map(Json.write(new SavedVo(true))));
        assertEquals(Map.of("deleted", true), Json.map(Json.write(new DeletedVo(true))));
    }

    @Test
    void pagingKeepsBoundsAndJsonShapeWithoutLoggingItems() {
        assertEquals(new PageQuery(1, 1), new PageQuery(-2, 0));
        assertEquals(100, new PageQuery(2, 101).size());
        assertEquals(214748364600L, new PageQuery(Integer.MAX_VALUE, 100).offset());
        var result = new PageVo<>(List.of("fixture-list-private-value"), 1L, 1, 20);
        assertEquals(
                Set.of("items", "total", "page", "size"), Json.map(Json.write(result)).keySet());
        assertEquals(
                "fixture-list-private-value",
                Json.tree(Json.write(result)).path("items").get(0).asString());
        assertFalse(result.toString().contains("fixture-list-private-value"));
    }
}
