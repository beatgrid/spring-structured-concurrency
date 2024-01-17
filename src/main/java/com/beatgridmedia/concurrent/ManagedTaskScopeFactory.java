package com.beatgridmedia.concurrent;

import jakarta.annotation.Nonnull;

import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;

/**
 * Factory for the {@link ManagedTaskScope} instances.
 *
 * The following snippet demonstrates how to configure two managed task scopes in a Spring Boot application:
 * <pre>
 * @Configuration
 * public class TaskScopeFactoryConfiguration {
 *
 *     @Qualifier("sharedScope")
 *     @ConfigurationProperties(prefix = "threads.shared")
 *     @Bean
 *     public ManagedTaskScopeFactory sharedScope() {
 *         return new ManagedTaskScopeFactoryImpl();
 *     }
 *
 *     @Qualifier("businessLogicScope")
 *     @ConfigurationProperties(prefix = "threads.business-logic")
 *     @Bean
 *     public ManagedTaskScopeFactory businessLogicScope(PlatformTransactionManager transactionManager) {
 *         ManagedTaskScopeFactoryImpl factory = new ManagedTaskScopeFactoryImpl();
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
 *  @author Leon van Zantvoort
 */
public interface ManagedTaskScopeFactory extends StructuredTaskScopeFactory<ManagedTaskScope> {

}
