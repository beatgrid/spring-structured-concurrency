package com.beatgridmedia.concurrent.managed;

import com.beatgridmedia.concurrent.StructuredTaskScopeFactory;
import com.beatgridmedia.concurrent.managed.ManagedTaskScope.ShutdownOnFailure;

/**
 * Factory for the {@link ShutdownOnFailure} instances.
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
 *     public ShutdownOnFailureFactory sharedScope() {
 *         return new ShutdownOnFailureFactoryImpl();
 *     }
 *
 *     @Qualifier("businessLogicScope")
 *     @ConfigurationProperties(prefix = "threads.business-logic")
 *     @Bean
 *     public ShutdownOnFailureFactory businessLogicScope(PlatformTransactionManager transactionManager) {
 *         ShutdownOnFailureFactoryImpl factory = new ShutdownOnFailureFactoryImpl();
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
 * @author Leon van Zantvoort */
public interface ShutdownOnFailureFactory extends StructuredTaskScopeFactory {

    /**
     * Creates a new instance of the {@link ShutdownOnFailure} scope supported by this factory.
     *
     * @return a new instance of the {@link ShutdownOnFailure} scope supported by this factory.
     */
    ShutdownOnFailure create();
}
