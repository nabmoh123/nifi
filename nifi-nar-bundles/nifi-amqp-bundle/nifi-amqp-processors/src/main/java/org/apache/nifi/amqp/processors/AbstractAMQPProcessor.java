/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.amqp.processors;

import com.rabbitmq.client.Address;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultSaslConfig;
import com.rabbitmq.client.impl.DefaultExceptionHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import javax.net.ssl.SSLContext;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.security.util.ClientAuth;
import org.apache.nifi.ssl.SSLContextService;


/**
 * Base processor that uses RabbitMQ client API
 * (https://www.rabbitmq.com/api-guide.html) to rendezvous with AMQP-based
 * messaging systems version 0.9.1
 *
 * @param <T> the type of {@link AMQPWorker}. Please see {@link AMQPPublisher}
 *            and {@link AMQPConsumer}
 */
abstract class AbstractAMQPProcessor<T extends AMQPWorker> extends AbstractProcessor {

    public static final PropertyDescriptor BROKERS = new PropertyDescriptor.Builder()
            .name("Brokers")
            .description("A comma-separated list of known AMQP Brokers in the format <host>:<port> (e.g., localhost:5672). If this is " +
                    "set, Host Name and Port are ignored. Only include hosts from the same AMQP cluster.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.HOSTNAME_PORT_LIST_VALIDATOR)
            .build();
    public static final PropertyDescriptor HOST = new PropertyDescriptor.Builder()
            .name("Host Name")
            .description("Network address of AMQP broker (e.g., localhost). If Brokers is set, then this property is ignored.")
            .required(false)
            .defaultValue("localhost")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.ATTRIBUTE_EXPRESSION_LANGUAGE_VALIDATOR)
            .build();
    public static final PropertyDescriptor PORT = new PropertyDescriptor.Builder()
            .name("Port")
            .description("Numeric value identifying Port of AMQP broker (e.g., 5671). If Brokers is set, then this property is ignored.")
            .required(false)
            .defaultValue("5672")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .build();
    public static final PropertyDescriptor V_HOST = new PropertyDescriptor.Builder()
            .name("Virtual Host")
            .description("Virtual Host name which segregates AMQP system for enhanced security.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
            .build();
    public static final PropertyDescriptor USER = new PropertyDescriptor.Builder()
            .name("User Name")
            .description("User Name used for authentication and authorization.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
            .build();
    public static final PropertyDescriptor PASSWORD = new PropertyDescriptor.Builder()
            .name("Password")
            .description("Password used for authentication and authorization.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();
    public static final PropertyDescriptor AMQP_VERSION = new PropertyDescriptor.Builder()
            .name("AMQP Version")
            .description("AMQP Version. Currently only supports AMQP v0.9.1.")
            .required(true)
            .allowableValues("0.9.1")
            .defaultValue("0.9.1")
            .build();
    public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("ssl-context-service")
            .displayName("SSL Context Service")
            .description("The SSL Context Service used to provide client certificate information for TLS/SSL connections.")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .build();
    public static final PropertyDescriptor USE_CERT_AUTHENTICATION = new PropertyDescriptor.Builder()
            .name("cert-authentication")
            .displayName("Use Client Certificate Authentication")
            .description("Authenticate using the SSL certificate rather than user name/password.")
            .required(false)
            .defaultValue("false")
            .allowableValues("true", "false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();
    public static final PropertyDescriptor CLIENT_AUTH = new PropertyDescriptor.Builder()
            .name("ssl-client-auth")
            .displayName("Client Auth")
            .description("The property has no effect and therefore deprecated.")
            .required(false)
            .allowableValues(ClientAuth.values())
            .defaultValue("NONE")
            .build();

    private static final List<PropertyDescriptor> propertyDescriptors;

    static {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(BROKERS);
        properties.add(HOST);
        properties.add(PORT);
        properties.add(V_HOST);
        properties.add(USER);
        properties.add(PASSWORD);
        properties.add(AMQP_VERSION);
        properties.add(SSL_CONTEXT_SERVICE);
        properties.add(USE_CERT_AUTHENTICATION);
        properties.add(CLIENT_AUTH);
        propertyDescriptors = Collections.unmodifiableList(properties);
    }

    protected static List<PropertyDescriptor> getCommonPropertyDescriptors() {
        return propertyDescriptors;
    }

    private BlockingQueue<AMQPResource<T>> resourceQueue;

    @OnScheduled
    public void onScheduled(ProcessContext context) {
        resourceQueue = new LinkedBlockingQueue<>(context.getMaxConcurrentTasks());
    }

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext context) {
        List<ValidationResult> results = new ArrayList<>(super.customValidate(context));

        boolean userConfigured = context.getProperty(USER).isSet();
        boolean passwordConfigured = context.getProperty(PASSWORD).isSet();
        boolean sslServiceConfigured = context.getProperty(SSL_CONTEXT_SERVICE).isSet();
        boolean useCertAuthentication = context.getProperty(USE_CERT_AUTHENTICATION).asBoolean();

        if (useCertAuthentication && (userConfigured || passwordConfigured)) {
            results.add(new ValidationResult.Builder()
                    .subject("Authentication configuration")
                    .valid(false)
                    .explanation(String.format("'%s' with '%s' and '%s' cannot be configured at the same time",
                            USER.getDisplayName(), PASSWORD.getDisplayName(),
                            USE_CERT_AUTHENTICATION.getDisplayName()))
                    .build());
        }

        if (!useCertAuthentication && (!userConfigured || !passwordConfigured)) {
            results.add(new ValidationResult.Builder()
                    .subject("Authentication configuration")
                    .valid(false)
                    .explanation(String.format("either '%s' with '%s' or '%s' must be configured",
                            USER.getDisplayName(), PASSWORD.getDisplayName(),
                            USE_CERT_AUTHENTICATION.getDisplayName()))
                    .build());
        }

        if (useCertAuthentication && !sslServiceConfigured) {
            results.add(new ValidationResult.Builder()
                    .subject("SSL configuration")
                    .valid(false)
                    .explanation(String.format("'%s' has been set but no '%s' configured",
                            USE_CERT_AUTHENTICATION.getDisplayName(), SSL_CONTEXT_SERVICE.getDisplayName()))
                    .build());
        }
        return results;
    }

    /**
     * Will builds target resource ({@link AMQPPublisher} or {@link AMQPConsumer}) upon first invocation and will delegate to the
     * implementation of {@link #processResource} method for further processing.
     */
    @Override
    public final void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        AMQPResource<T> resource = resourceQueue.poll();
        if (resource == null) {
            try {
                resource = createResource(context);
            } catch (Exception e) {
                getLogger().error("Failed to initialize AMQP client", e);
                context.yield();
                return;
            }
        }

        try {
            processResource(resource.getConnection(), resource.getWorker(), context, session);

            if (!resourceQueue.offer(resource)) {
                getLogger().info("Worker queue is full, closing AMQP client");
                closeResource(resource);
            }
        } catch (AMQPException | AMQPRollbackException e) {
            getLogger().error("AMQP failure, dropping the client", e);
            context.yield();
            closeResource(resource);
        } catch (Exception e) {
            getLogger().error("Processor failure", e);
            context.yield();
        }
    }


    @OnStopped
    public void close() {
        if (resourceQueue != null) {
            AMQPResource<T> resource;
            while ((resource = resourceQueue.poll()) != null) {
                closeResource(resource);
            }
            resourceQueue = null;
        }
    }

    private void closeResource(AMQPResource<T> resource) {
        try {
            resource.close();
        } catch (Exception e) {
            getLogger().error("Failed to close AMQP Connection", e);
        }
    }

    /**
     * Performs functionality of the Processor, given the appropriate connection and worker
     */
    protected abstract void processResource(Connection connection, T worker, ProcessContext context, ProcessSession session) throws ProcessException;

    /**
     * Builds the appropriate AMQP Worker for the implementing processor
     *
     * @param context instance of {@link ProcessContext}
     * @return new instance of {@link AMQPWorker}
     */
    protected abstract T createAMQPWorker(ProcessContext context, Connection connection);


    private AMQPResource<T> createResource(final ProcessContext context) {
        Connection connection = null;
        try {
            ExecutorService executor = Executors.newSingleThreadExecutor(new BasicThreadFactory.Builder()
                    .namingPattern("AMQP Consumer: " + getIdentifier())
                    .build());
            connection = createConnection(context, executor);
            T worker = createAMQPWorker(context, connection);
            return new AMQPResource<>(connection, worker, executor);
        } catch (Exception e) {
            if (connection != null && connection.isOpen()) {
                try {
                    connection.close();
                } catch (Exception closingEx) {
                    getLogger().error("Failed to close AMQP Connection", closingEx);
                }
            }
            throw e;
        }
    }

    private Address[] createHostsList(final ProcessContext context) {
        String evaluatedUrls = context.getProperty(BROKERS).evaluateAttributeExpressions().getValue();
        return Address.parseAddresses(evaluatedUrls);
    }

    protected Connection createConnection(ProcessContext context, ExecutorService executor) {
        final ConnectionFactory cf = new ConnectionFactory();
        cf.setUsername(context.getProperty(USER).evaluateAttributeExpressions().getValue());
        cf.setPassword(context.getProperty(PASSWORD).getValue());

        final String vHost = context.getProperty(V_HOST).evaluateAttributeExpressions().getValue();
        if (vHost != null) {
            cf.setVirtualHost(vHost);
        }

        // handles TLS/SSL aspects
        final SSLContextService sslService = context.getProperty(SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
        final Boolean useCertAuthentication = context.getProperty(USE_CERT_AUTHENTICATION).asBoolean();

        if (sslService != null) {
            final SSLContext sslContext = sslService.createContext();
            cf.useSslProtocol(sslContext);

            if (useCertAuthentication) {
                // this tells the factory to use the client certificate for authentication and not user name and password
                // REF: https://github.com/rabbitmq/rabbitmq-auth-mechanism-ssl
                cf.setSaslConfig(DefaultSaslConfig.EXTERNAL);
            }
        }

        cf.setAutomaticRecoveryEnabled(false);
        cf.setExceptionHandler(new DefaultExceptionHandler() {
            @Override
            public void handleUnexpectedConnectionDriverException(Connection conn, Throwable exception) {
                getLogger().error("Connection lost to server {}:{}.", new Object[]{conn.getAddress(), conn.getPort()}, exception);
            }
        });

        try {
            Connection connection;
            if (context.getProperty(BROKERS).isSet()) {
                Address[] hostsList = createHostsList(context);
                connection = cf.newConnection(executor, hostsList);
            } else {
                cf.setHost(context.getProperty(BROKERS).evaluateAttributeExpressions().getValue());
                cf.setPort(Integer.parseInt(context.getProperty(PORT).evaluateAttributeExpressions().getValue()));
                connection = cf.newConnection(executor);
            }

            return connection;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to establish connection with AMQP Broker: " + cf.toString(), e);
        }
    }
}
