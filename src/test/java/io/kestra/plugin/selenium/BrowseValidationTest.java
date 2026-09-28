package io.kestra.plugin.selenium;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.selenium.Browse.Action;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that {@code @Valid} on the actions list cascades bean validation into each Action item.
 */
@KestraTest
class BrowseValidationTest {

    @Inject
    private ModelValidator modelValidator;

    @Test
    void givenActionMissingActionType_whenValidated_thenViolationReported() {
        var task = browse()
            .actions(List.of(Action.builder().selector(Property.ofValue("h1")).build()))
            .build();

        var violation = modelValidator.isValid(task);

        assertThat(violation.isPresent(), is(true));
        assertThat(violation.get().getMessage(), containsString("action"));
    }

    @Test
    void givenActionWithActionType_whenValidated_thenNoViolation() {
        var task = browse()
            .actions(List.of(Action.builder().action(Browse.ActionType.EXTRACT_TEXT).selector(Property.ofValue("h1")).build()))
            .build();

        assertThat(modelValidator.isValid(task).isPresent(), is(false));
    }

    private Browse.BrowseBuilder<?, ?> browse() {
        return Browse.builder()
            .id(IdUtils.create())
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue("http://localhost:4444"));
    }
}
