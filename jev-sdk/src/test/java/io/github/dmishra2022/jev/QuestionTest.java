package io.github.dmishra2022.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.entry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class QuestionTest {

    enum Department implements ChoiceOption {
        BILLING("Payments, invoicing, refunds"),
        TECHNICAL(null);

        private final String description;

        Department(String description) {
            this.description = description;
        }

        @Override
        public Object description() {
            return description;
        }
    }

    enum Plain { RED, GREEN }

    @Nested
    class Nouls {
        @Test
        void minimalNoulHasNoCriteria() {
            NoulQuestion q = Question.noul("Is it urgent?");
            assertThat(q.type()).isEqualTo(QuestionType.NOUL);
            assertThat(q.instructions()).isEqualTo("Is it urgent?");
            assertThat(q.hasCriteria()).isFalse();
        }

        @Test
        void criteriaOnEitherSideCount() {
            assertThat(Question.noul("q", "yes", null).hasCriteria()).isTrue();
            assertThat(Question.noul("q", null, "no").hasCriteria()).isTrue();
            assertThat(Question.noul("q", null, null).hasCriteria()).isFalse();
        }

        @Test
        void structuredInstructionsAreAllowed() {
            var instructions = Map.of("question", "Same person as `candidate`?", "candidate", Map.of("name", "J"));
            assertThat(Question.noul(instructions).instructions()).isEqualTo(instructions);
        }

        @Test
        void instructionsAreValidated() {
            assertThatNullPointerException().isThrownBy(() -> Question.noul(null)).withMessage("instructions");
            assertThatIllegalArgumentException().isThrownBy(() -> Question.noul("  "))
                    .withMessage("instructions must not be blank");
        }
    }

    @Nested
    class Choices {
        @Test
        void varargsOptionsKeepOrderAndHaveNullDescriptions() {
            ChoiceQuestion q = Question.choice("tone?", "calm", "angry", "excited");
            assertThat(q.type()).isEqualTo(QuestionType.CHOICE);
            assertThat(q.optionNames()).containsExactly("calm", "angry", "excited");
            assertThat(q.options()).containsEntry("calm", null);
        }

        @Test
        void varargsRejectDuplicatesBlankAndNull() {
            assertThatIllegalArgumentException().isThrownBy(() -> Question.choice("q", "a", "a"))
                    .withMessage("duplicate option: a");
            assertThatIllegalArgumentException().isThrownBy(() -> Question.choice("q", "a", " "));
            assertThatNullPointerException().isThrownBy(() -> Question.choice("q", (String[]) null));
            assertThatNullPointerException().isThrownBy(() -> Question.choice("q", "a", null));
            assertThatIllegalArgumentException().isThrownBy(() -> Question.choice("q", new String[0]))
                    .withMessage("a choice needs at least one option");
        }

        @Test
        void mapOptionsKeepOrderAndAllowStructuredOrNullDescriptions() {
            var options = new LinkedHashMap<String, Object>();
            options.put("billing", "Payments");
            options.put("technical", Map.of("covers", List.of("bugs")));
            options.put("sales", null);
            ChoiceQuestion q = Question.choice("team?", options);
            assertThat(q.optionNames()).containsExactly("billing", "technical", "sales");
            options.put("other", "x");
            assertThat(q.options()).hasSize(3);
        }

        @Test
        void optionsMapIsImmutable() {
            ChoiceQuestion q = Question.choice("q", "a");
            assertThatThrownByUnsupported(() -> q.options().put("b", null));
        }

        @Test
        void enumOptionsUseLowerCaseNamesAndDescriptions() {
            ChoiceQuestion q = Question.choice("team?", Department.class);
            assertThat(q.optionNames()).containsExactly("billing", "technical");
            assertThat(q.options()).containsEntry("billing", "Payments, invoicing, refunds")
                    .containsEntry("technical", null);
            assertThat(Question.choice("color?", Plain.class).options()).containsEntry("red", null);
            assertThatNullPointerException().isThrownBy(() -> Question.choice("q", (Class<Plain>) null));
        }

        @Test
        void limitsAreEnforced() {
            var many = new HashMap<String, Object>();
            IntStream.range(0, ChoiceQuestion.MAX_OPTIONS).forEach(i -> many.put("o" + i, null));
            assertThat(Question.choice("q", many).options()).hasSize(255);
            many.put("one-too-many", null);
            assertThatIllegalArgumentException().isThrownBy(() -> Question.choice("q", many))
                    .withMessage("a choice accepts at most 255 options but got 256");
            assertThatIllegalArgumentException().isThrownBy(() -> Question.choice("q", Map.of()));
            assertThatNullPointerException().isThrownBy(() -> Question.choice("q", (Map<String, ?>) null));
        }

        @Test
        void builderAddsAndReplacesOptions() {
            ChoiceQuestion q = ChoiceQuestion.builder("q")
                    .option("a")
                    .option("b", "first")
                    .option("b", "second")
                    .build();
            assertThat(q.options()).containsExactly(entry("a", null), entry("b", "second"));
            assertThatIllegalArgumentException().isThrownBy(() -> ChoiceQuestion.builder("q").option(""));
        }
    }

    @Nested
    class Scores {
        @Test
        void varargsAndListFactoriesKeepOrder() {
            ScoreQuestion q = Question.score("how angry?", "Calm", "Frustrated", "Very angry");
            assertThat(q.type()).isEqualTo(QuestionType.SCORE);
            assertThat(q.levels()).containsExactly("Calm", "Frustrated", "Very angry");
            assertThat(Question.score("q", List.of("low", Map.of("label", "high"))).levels()).hasSize(2);
        }

        @Test
        void levelsMayBeNullAndAreDefensivelyCopied() {
            var levels = new ArrayList<Object>(Arrays.asList("low", null));
            ScoreQuestion q = new ScoreQuestion("q", levels);
            levels.add("extra");
            assertThat(q.levels()).containsExactly("low", null);
            assertThatThrownByUnsupported(() -> q.levels().add("x"));
        }

        @Test
        void levelCountIsBounded() {
            assertThatIllegalArgumentException().isThrownBy(() -> Question.score("q", "only"))
                    .withMessage("a score needs between 2 and 10 levels but got 1");
            Object[] eleven = IntStream.range(0, 11).mapToObj(i -> "l" + i).toArray();
            assertThatIllegalArgumentException().isThrownBy(() -> Question.score("q", eleven));
            Object[] ten = Arrays.copyOf(eleven, 10);
            assertThat(Question.score("q", ten).levels()).hasSize(ScoreQuestion.MAX_LEVELS);
            assertThatNullPointerException().isThrownBy(() -> Question.score("q", (Object[]) null));
            assertThatNullPointerException().isThrownBy(() -> Question.score("q", (List<?>) null));
            assertThatNullPointerException().isThrownBy(() -> new ScoreQuestion("q", null));
        }
    }

    @Test
    void wireNamesRoundTrip() {
        for (QuestionType type : QuestionType.values()) {
            assertThat(QuestionType.fromWireName(type.wireName())).contains(type);
        }
        assertThat(QuestionType.fromWireName("essay")).isEmpty();
    }

    private static void assertThatThrownByUnsupported(Runnable action) {
        org.assertj.core.api.Assertions.assertThatThrownBy(action::run)
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
