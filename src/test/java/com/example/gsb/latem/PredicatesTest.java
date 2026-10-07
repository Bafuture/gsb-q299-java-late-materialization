package com.example.gsb.latem;

import com.example.gsb.latem.predicate.Predicates;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PredicatesTest {

    @Test
    void intBetweenIsInclusiveOnBothEnds() {
        var predicate = Predicates.intBetween(3, 7);

        assertThat(predicate.testInt(2)).isFalse();
        assertThat(predicate.testInt(3)).isTrue();
        assertThat(predicate.testInt(7)).isTrue();
        assertThat(predicate.testInt(8)).isFalse();
    }

    @Test
    void intBetweenRejectsInvertedRange() {
        assertThatThrownBy(() -> Predicates.intBetween(7, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doublePredicatesCompareCorrectly() {
        assertThat(Predicates.doubleLessThan(0.5).testDouble(0.5)).isFalse();
        assertThat(Predicates.doubleLessThan(0.5).testDouble(0.49)).isTrue();
        assertThat(Predicates.doubleBetween(0.1, 0.2).testDouble(0.15)).isTrue();
        assertThat(Predicates.doubleBetween(0.1, 0.2).testDouble(0.21)).isFalse();
    }

    @Test
    void stringPredicatesMatch() {
        assertThat(Predicates.stringEq("red").testString("red")).isTrue();
        assertThat(Predicates.stringEq("red").testString("blue")).isFalse();
        assertThat(Predicates.stringIn(List.of("a", "b")).testString("b")).isTrue();
        assertThat(Predicates.stringIn(List.of("a", "b")).testString("c")).isFalse();
        assertThat(Predicates.stringStartsWith("pre").testString("prefix")).isTrue();
        assertThat(Predicates.stringStartsWith("pre").testString("suffix")).isFalse();
    }

    @Test
    void mismatchedAccessorThrows() {
        assertThatThrownBy(() -> Predicates.intEq(1).testString("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
