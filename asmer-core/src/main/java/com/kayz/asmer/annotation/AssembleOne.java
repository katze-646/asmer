package com.kayz.asmer.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for <em>many-to-one</em> assembly.
 *
 * <p>Asmer will:
 * <ol>
 *   <li>Collect {@code keyField} values from all parent objects in the batch.</li>
 *   <li>Invoke the loader once with the deduplicated key set.</li>
 *   <li>Set the matching result onto each parent via the derived setter.</li>
 * </ol>
 *
 * <pre>{@code
 * @AssembleOne(keyField = "userId")
 * private UserEntity user;
 *
 * // then:
 * Asmer.of(orders)
 *     .on(Order::getUser, userRepo::findByIdIn, User::getId)
 *     .assemble();
 * }</pre>
 *
 * @see AssembleMany
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AssembleOne {

    /**
     * The field name on the parent entity whose value is used as the batch key.
     * For example, {@code "userId"} resolves to the {@code getUserId()} getter.
     */
    String keyField();
}
