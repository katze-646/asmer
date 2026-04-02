package com.kayz.asmer.internal;

/** Distinguishes many-to-one ({@code ONE}) from one-to-many ({@code MANY}) assembly rules. */
public enum RuleKind {
    /** {@code @AssembleOne} — a single value set on each parent. */
    ONE,
    /** {@code @AssembleMany} — a {@code List} of values set on each parent. */
    MANY
}
