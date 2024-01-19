package com.beatgridmedia.concurrent.managed;

import com.beatgridmedia.concurrent.StructuredTaskScopeFactory;
import com.beatgridmedia.concurrent.managed.ManagedTaskScope.CollectingScope;

/**
 * Factory for the {@link CollectingScope} instances.
 *
 * The following snippet demonstrates how to configure two task scopes in a Spring Boot application:
 *
 * <pre>
 * @Configuration
 * public class TaskScopeFactoryConfiguration {
 *
 *     @Qualifier("sharedScope")
 *     @ConfigurationProperties(prefix = "threads.shared")
 *     @Bean
 *     public CollectingScopeFactory sharedScope() {
 *         return new CollectingScopeFactoryImpl();
 *     }
 *
 *     @Qualifier("businessLogicScope")
 *     @ConfigurationProperties(prefix = "threads.business-logic")
 *     @Bean
 *     public CollectingScopeFactory businessLogicScope(PlatformTransactionManager transactionManager) {
 *         CollectingScopeFactoryImpl factory = new CollectingScopeFactoryImpl();
 *         factory.setTransactionTemplate(new TransactionTemplate(transactionManager));
 *         return factory;
 *     }
 * }
 * </pre>
 *
 * The configuration properties for both scopes:
 * <pre>
 * threads.shared.name=shared
 * threads.shared.virtual=true
 *
 * threads.business-logic.name=business-logic
 * threads.business-logic.thread-count=4
 * threads.business-logic.shared-thread-count=8
 * threads.business-logic.virtual=true
 * </pre>
 *
 * @author Leon van Zantvoort
 */
public interface CollectingScopeFactory extends StructuredTaskScopeFactory {

    /**
     * Creates a new instance of the {@link CollectingScope} scope supported by this factory.
     *
     * @param <T> the type of the result of the task.
     * @return a new instance of the {@link CollectingScope} scope supported by this factory.
     */
    <T> CollectingScope<T> create();
}
