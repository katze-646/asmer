package com.kayz.asmer.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code List} field for <em>one-to-many</em> assembly.
 *
 * <p>Asmer will:
 * <ol>
 *   <li>Collect {@code keyField} values from all parent objects in the batch.</li>
 *   <li>Invoke the loader once with the deduplicated key set.</li>
 *   <li>Group results by the child's foreign key and set each group onto its parent.</li>
 * </ol>
 *
 * <pre>{@code
 * @AssembleMany(keyField = "id")
 * private List<OrderItem> items;
 *
 * // then:
 * Asmer.of(orders)
 *     .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
 *     .assemble();
 * }</pre>
 *
 * @see AssembleOne
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AssembleMany {

    /**
     * The field name on the parent entity whose value is used as the batch key.
     * For example, {@code "id"} resolves to the {@code getId()} getter.
     */
    String keyField();
}
