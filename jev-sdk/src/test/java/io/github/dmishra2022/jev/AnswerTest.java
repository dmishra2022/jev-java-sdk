package io.github.dmishra2022.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnswerTest {

    enum Tone { CALM, ANGRY }

    @Nested
    class Nouls {
        @Test
        void thresholds() {
            var answer = new NoulAnswer(0.7);
            assertThat(answer.type()).isEqualTo(QuestionType.NOUL);
            assertThat(answer.isTrue()).isTrue();
            assertThat(new NoulAnswer(0.5).isTrue()).isTrue();
            assertThat(new NoulAnswer(0.49).isTrue()).isFalse();
            assertThat(answer.isTrue(0.7)).isTrue();
            assertThat(answer.isTrue(0.71)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(doubles = {-0.01, 1.01, Double.NaN})
        void thresholdOutsideUnitIntervalIsRejected(double threshold) {
            assertThatIllegalArgumentException().isThrownBy(() -> new NoulAnswer(0.5).isTrue(threshold))
                    .withMessageStartingWith("threshold must be within [0, 1]");
        }

        @ParameterizedTest
        @CsvSource({"0.95, YES", "0.9, YES", "0.5, UNCERTAIN", "0.1, NO", "0.0, NO", "0.11, UNCERTAIN"})
        void verdict(double noul, NoulAnswer.Verdict expected) {
            assertThat(new NoulAnswer(noul).verdict(0.1, 0.9)).isEqualTo(expected);
        }

        @Test
        void verdictValidatesBounds() {
            var answer = new NoulAnswer(0.5);
            assertThatIllegalArgumentException().isThrownBy(() -> answer.verdict(0.9, 0.1))
                    .withMessage("noAtOrBelow must be below yesAtOrAbove");
            assertThatIllegalArgumentException().isThrownBy(() -> answer.verdict(0.5, 0.5));
            assertThatIllegalArgumentException().isThrownBy(() -> answer.verdict(-1, 0.5));
            assertThatIllegalArgumentException().isThrownBy(() -> answer.verdict(0.1, 2));
        }
    }

    @Nested
    class Choices {
        private final ChoiceAnswer answer = new ChoiceAnswer("billing",
                ordered("billing", 0.7, "technical", 0.2, "sales", 0.1), 0.8);

        @Test
        void accessors() {
            assertThat(answer.type()).isEqualTo(QuestionType.CHOICE);
            assertThat(answer.probability("technical")).isEqualTo(0.2);
            assertThat(answer.isConfident(0.8)).isTrue();
            assertThat(answer.isConfident(0.81)).isFalse();
            assertThatIllegalArgumentException().isThrownBy(() -> answer.isConfident(1.5));
        }

        @Test
        void unknownOptionIsAnError() {
            assertThatIllegalArgumentException().isThrownBy(() -> answer.probability("legal"))
                    .withMessageContaining("unknown option 'legal'");
        }

        @Test
        void rankedSortsDescendingAndKeepsServerOrderOnTies() {
            var tied = new ChoiceAnswer("b", ordered("a", 0.25, "b", 0.5, "c", 0.25), 0.5);
            assertThat(tied.ranked()).extracting(Map.Entry::getKey).containsExactly("b", "a", "c");
        }

        @Test
        void margin() {
            assertThat(answer.margin()).isCloseTo(0.5, within(1e-9));
            assertThat(new ChoiceAnswer("a", Map.of("a", 1.0), 1).margin()).isEqualTo(1.0);
            assertThat(new ChoiceAnswer("a", Map.of(), 0).margin()).isZero();
        }

        @Test
        void mapsBackToEnumIgnoringCase() {
            assertThat(new ChoiceAnswer("angry", Map.of(), 1).as(Tone.class)).isEqualTo(Tone.ANGRY);
            assertThat(new ChoiceAnswer("CALM", Map.of(), 1).as(Tone.class)).isEqualTo(Tone.CALM);
            assertThatIllegalArgumentException().isThrownBy(() -> new ChoiceAnswer("happy", Map.of(), 1).as(Tone.class))
                    .withMessageContaining("is not a constant of");
            assertThatNullPointerException().isThrownBy(() -> answer.as(null));
        }

        @Test
        void validatesAndCopies() {
            assertThatNullPointerException().isThrownBy(() -> new ChoiceAnswer(null, Map.of(), 1));
            assertThatNullPointerException().isThrownBy(() -> new ChoiceAnswer("a", null, 1));
            var source = new LinkedHashMap<String, Double>(Map.of("a", 1.0));
            var copy = new ChoiceAnswer("a", source, 1);
            source.put("b", 0.0);
            assertThat(copy.probabilities()).containsOnlyKeys("a");
        }
    }

    @Nested
    class Scores {
        private final Map<Integer, Object> legend = Map.of(0, "Calm", 1, "Frustrated", 2, "Very angry");

        @Test
        void levelRoundsAndClamps() {
            assertThat(score(1.05).type()).isEqualTo(QuestionType.SCORE);
            assertThat(score(1.05).level()).isEqualTo(1);
            assertThat(score(1.5).level()).isEqualTo(2);
            assertThat(score(1.05).levelDescription()).isEqualTo("Frustrated");
            assertThat(score(7.0).level()).isEqualTo(2);
            assertThat(score(-3.0).level()).isZero();
        }

        @Test
        void levelWithoutLegendJustRounds() {
            var bare = new ScoreAnswer(2.6, Map.<Integer, Object>of(), Map.<Integer, Double>of(), 0.5);
            assertThat(bare.level()).isEqualTo(3);
            assertThat(bare.levelDescription()).isNull();
            assertThat(bare.normalized()).isZero();
        }

        @Test
        void normalizedSpansLegendRange() {
            assertThat(score(1.0).normalized()).isEqualTo(0.5);
            assertThat(score(2.0).normalized()).isEqualTo(1.0);
            assertThat(score(9.0).normalized()).isEqualTo(1.0);
            assertThat(score(-1.0).normalized()).isZero();
        }

        @Test
        void normalizedHandlesTwoLevelAndOffsetLegends() {
            Map<Integer, Double> none = Map.of();
            assertThat(new ScoreAnswer(1.0, Map.<Integer, Object>of(0, "no", 1, "yes"), none, 1).normalized())
                    .isEqualTo(1.0);
            assertThat(new ScoreAnswer(2.0, Map.<Integer, Object>of(1, "a", 2, "b", 3, "c"), none, 1).normalized())
                    .isEqualTo(0.5);
        }

        @Test
        void mapsAreSortedAndImmutable() {
            var answer = score(1.0);
            assertThat(answer.legend().keySet()).containsExactly(0, 1, 2);
            assertThat(answer.probabilities().firstKey()).isZero();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> answer.legend().put(3, "x"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(answer.isConfident(0.9)).isTrue();
        }

        @Test
        void nullMapsAreRejected() {
            assertThatNullPointerException().isThrownBy(
                    () -> new ScoreAnswer(1, (TreeMap<Integer, Object>) null, new TreeMap<>(), 1));
            assertThatNullPointerException().isThrownBy(
                    () -> new ScoreAnswer(1, new TreeMap<>(), (TreeMap<Integer, Double>) null, 1));
        }

        private ScoreAnswer score(double value) {
            return new ScoreAnswer(value, legend, Map.of(0, 0.0, 1, 0.95, 2, 0.05), 0.92);
        }
    }

    @Test
    void sealedHierarchySupportsExhaustiveSwitch() {
        Answer[] answers = {new NoulAnswer(1), new ChoiceAnswer("a", Map.of(), 1),
                new ScoreAnswer(0, Map.<Integer, Object>of(), Map.<Integer, Double>of(), 1)};
        for (Answer answer : answers) {
            String kind = switch (answer) {
                case NoulAnswer n -> "noul";
                case ChoiceAnswer c -> "choice";
                case ScoreAnswer s -> "score";
            };
            assertThat(kind).isEqualTo(answer.type().wireName());
        }
    }

    static Map<String, Double> ordered(Object... keyValues) {
        var map = new LinkedHashMap<String, Double>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], (Double) keyValues[i + 1]);
        }
        return map;
    }
}
