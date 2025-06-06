/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.processor;

import java.util.Map;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.AsyncCallback;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.CamelExchangeException;
import org.apache.camel.Component;
import org.apache.camel.Consumer;
import org.apache.camel.DynamicPollingConsumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Expression;
import org.apache.camel.NoTypeConversionAvailableException;
import org.apache.camel.PollingConsumer;
import org.apache.camel.ResolveEndpointFailedException;
import org.apache.camel.spi.ConsumerCache;
import org.apache.camel.spi.EndpointUtilizationStatistics;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.spi.HeadersMapFactory;
import org.apache.camel.spi.IdAware;
import org.apache.camel.spi.PollDynamicAware;
import org.apache.camel.spi.RouteIdAware;
import org.apache.camel.support.AsyncProcessorSupport;
import org.apache.camel.support.BridgeExceptionHandlerToErrorHandler;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.EndpointHelper;
import org.apache.camel.support.EventDrivenPollingConsumer;
import org.apache.camel.support.ExchangeHelper;
import org.apache.camel.support.cache.DefaultConsumerCache;
import org.apache.camel.support.service.ServiceHelper;
import org.apache.camel.util.URISupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.support.ExchangeHelper.copyResultsPreservePattern;

/**
 * A content enricher that enriches input data by first obtaining additional data from a <i>resource</i> represented by
 * an endpoint <code>producer</code> and second by aggregating input data and additional data. Aggregation of input data
 * and additional data is delegated to an {@link AggregationStrategy} object.
 * <p/>
 * Uses a {@link org.apache.camel.PollingConsumer} to obtain the additional data as opposed to {@link Enricher} that
 * uses a {@link org.apache.camel.Producer}.
 *
 * @see PollProcessor
 * @see Enricher
 */
public class PollEnricher extends AsyncProcessorSupport implements IdAware, RouteIdAware, CamelContextAware {

    private static final Logger LOG = LoggerFactory.getLogger(PollEnricher.class);

    private PollDynamicAware dynamicAware;
    private volatile String scheme;
    private CamelContext camelContext;
    private ConsumerCache consumerCache;
    private HeadersMapFactory headersMapFactory;
    private String id;
    private String routeId;
    private String variableReceive;
    private AggregationStrategy aggregationStrategy;
    private final Expression expression;
    private final String uri;
    private long timeout;
    private boolean aggregateOnException;
    private int cacheSize;
    private boolean ignoreInvalidEndpoint;
    private boolean autoStartupComponents = true;
    private boolean allowOptimisedComponents = true;

    /**
     * Creates a new {@link PollEnricher}.
     *
     * @param expression expression to use to compute the endpoint to poll from.
     * @param uri        the endpoint to poll from.
     * @param timeout    timeout in millis
     */
    public PollEnricher(Expression expression, String uri, long timeout) {
        this.expression = expression;
        this.uri = uri;
        this.timeout = timeout;
    }

    /**
     * Creates a new {@link PollEnricher}.
     *
     * @param uri     the endpoint to poll from.
     * @param timeout timeout in millis
     */
    public PollEnricher(String uri, long timeout) {
        this.expression = null;
        this.uri = uri;
        this.timeout = timeout;
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getRouteId() {
        return routeId;
    }

    @Override
    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    public PollDynamicAware getDynamicAware() {
        return dynamicAware;
    }

    public String getUri() {
        return uri;
    }

    public Expression getExpression() {
        return expression;
    }

    public EndpointUtilizationStatistics getEndpointUtilizationStatistics() {
        return consumerCache.getEndpointUtilizationStatistics();
    }

    public AggregationStrategy getAggregationStrategy() {
        return aggregationStrategy;
    }

    /**
     * Sets the aggregation strategy for this poll enricher.
     *
     * @param aggregationStrategy the aggregationStrategy to set
     */
    public void setAggregationStrategy(AggregationStrategy aggregationStrategy) {
        this.aggregationStrategy = aggregationStrategy;
    }

    public String getVariableReceive() {
        return variableReceive;
    }

    public void setVariableReceive(String variableReceive) {
        this.variableReceive = variableReceive;
    }

    public long getTimeout() {
        return timeout;
    }

    /**
     * Sets the timeout to use when polling.
     * <p/>
     * Use 0 to use receiveNoWait, Use -1 to use receive with no timeout (which will block until data is available).
     *
     * @param timeout timeout in millis.
     */
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public boolean isAggregateOnException() {
        return aggregateOnException;
    }

    public void setAggregateOnException(boolean aggregateOnException) {
        this.aggregateOnException = aggregateOnException;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    public boolean isIgnoreInvalidEndpoint() {
        return ignoreInvalidEndpoint;
    }

    public void setIgnoreInvalidEndpoint(boolean ignoreInvalidEndpoint) {
        this.ignoreInvalidEndpoint = ignoreInvalidEndpoint;
    }

    public boolean isAutoStartupComponents() {
        return autoStartupComponents;
    }

    public void setAutoStartupComponents(boolean autoStartupComponents) {
        this.autoStartupComponents = autoStartupComponents;
    }

    public boolean isAllowOptimisedComponents() {
        return allowOptimisedComponents;
    }

    public void setAllowOptimisedComponents(boolean allowOptimisedComponents) {
        this.allowOptimisedComponents = allowOptimisedComponents;
    }

    /**
     * Enriches the input data (<code>exchange</code>) by first obtaining additional data from an endpoint represented
     * by an endpoint <code>producer</code> and second by aggregating input data and additional data. Aggregation of
     * input data and additional data is delegated to an {@link AggregationStrategy} object set at construction time. If
     * the message exchange with the resource endpoint fails then no aggregation will be done and the failed exchange
     * content is copied over to the original message exchange.
     *
     * @param exchange input data.
     */
    @Override
    public boolean process(Exchange exchange, AsyncCallback callback) {
        try {
            preCheckPoll(exchange);
        } catch (Exception e) {
            exchange.setException(new CamelExchangeException("Error during pre poll check", exchange, e));
            callback.done(true);
            return true;
        }

        // which consumer to use
        PollingConsumer consumer;
        Endpoint endpoint;

        // use dynamic endpoint so calculate the endpoint to use
        Object recipient = null;
        String staticUri = null;
        boolean prototype = cacheSize < 0;
        try {
            recipient = expression.evaluate(exchange, Object.class);
            if (dynamicAware != null) {
                // if its the same scheme as the pre-resolved dynamic aware then we can optimise to use it
                String originalUri = uri;
                String uri = resolveUri(exchange, recipient);
                String scheme = resolveScheme(exchange, uri);
                if (dynamicAware.getScheme().equals(scheme)) {
                    PollDynamicAware.DynamicAwareEntry entry = dynamicAware.prepare(exchange, uri, originalUri);
                    if (entry != null) {
                        staticUri = dynamicAware.resolveStaticUri(exchange, entry);
                        if (staticUri != null) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Optimising poll via PollDynamicAware component: {} to use static uri: {}", scheme,
                                        URISupport.sanitizeUri(staticUri));
                            }
                        }
                    }
                }
            }
            Object targetRecipient = staticUri != null ? staticUri : recipient;
            targetRecipient = prepareRecipient(exchange, targetRecipient);
            if (targetRecipient == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Poll dynamic evaluated as null so cannot poll from any endpoint");
                }
                // no endpoint to send to, so ignore
                callback.done(true);
                return true;
            }
            Endpoint existing = getExistingEndpoint(exchange, targetRecipient);
            if (existing == null) {
                endpoint = resolveEndpoint(exchange, targetRecipient, prototype);
            } else {
                endpoint = existing;
                // we have an existing endpoint then its not a prototype scope
                prototype = false;
            }

            // acquire the consumer from the cache
            consumer = consumerCache.acquirePollingConsumer(endpoint);
        } catch (Exception e) {
            if (isIgnoreInvalidEndpoint()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Endpoint uri is invalid: {}. This exception will be ignored.", recipient, e);
                }
            } else {
                exchange.setException(e);
            }
            callback.done(true);
            return true;
        }

        // grab the real delegate consumer that performs the actual polling
        final boolean bridgeErrorHandler = isBridgeErrorHandler(consumer);

        DynamicPollingConsumer dynamicConsumer = null;
        if (consumer instanceof DynamicPollingConsumer dyn) {
            dynamicConsumer = dyn;
        }

        Exchange resourceExchange;
        try {
            if (timeout < 0) {
                LOG.debug("Consumer receive: {}", consumer);
                resourceExchange = dynamicConsumer != null ? dynamicConsumer.receive(exchange) : consumer.receive();
            } else if (timeout == 0) {
                LOG.debug("Consumer receiveNoWait: {}", consumer);
                resourceExchange = dynamicConsumer != null ? dynamicConsumer.receiveNoWait(exchange) : consumer.receiveNoWait();
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Consumer receive with timeout: {} ms. {}", timeout, consumer);
                }
                resourceExchange
                        = dynamicConsumer != null ? dynamicConsumer.receive(exchange, timeout) : consumer.receive(timeout);
            }

            if (resourceExchange == null) {
                LOG.debug("Consumer received no exchange");
            } else {
                LOG.debug("Consumer received: {}", resourceExchange);
            }
        } catch (Exception e) {
            exchange.setException(new CamelExchangeException("Error during poll", exchange, e));
            callback.done(true);
            return true;
        } finally {
            // return the consumer back to the cache
            consumerCache.releasePollingConsumer(endpoint, consumer);
            // and stop prototype endpoints
            if (prototype) {
                ServiceHelper.stopAndShutdownService(endpoint);
            }
        }

        // remember current redelivery stats
        Object redelivered = exchange.getIn().getHeader(Exchange.REDELIVERED);
        Object redeliveryCounter = exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER);
        Object redeliveryMaxCounter = exchange.getIn().getHeader(Exchange.REDELIVERY_MAX_COUNTER);

        // if we are bridging error handler and failed then remember the caused exception
        Throwable cause = null;
        if (resourceExchange != null && bridgeErrorHandler) {
            cause = resourceExchange.getException();
        }

        // if we should store the received message body in a variable,
        // then we need to preserve the original message body
        Object originalBody = null;
        Map<String, Object> originalHeaders = null;
        if (variableReceive != null) {
            try {
                originalBody = exchange.getMessage().getBody();
                // do a defensive copy of the headers
                originalHeaders = headersMapFactory.newMap(exchange.getMessage().getHeaders());
            } catch (Exception throwable) {
                exchange.setException(throwable);
                callback.done(true);
                return true;
            }
        }

        try {
            if (!isAggregateOnException() && resourceExchange != null && resourceExchange.isFailed()) {
                // copy resource exchange onto original exchange (preserving pattern)
                // and preserve redelivery headers
                copyResultsPreservePattern(exchange, resourceExchange);
            } else {
                prepareResult(exchange);

                // prepare the exchanges for aggregation
                ExchangeHelper.prepareAggregation(exchange, resourceExchange);
                // must catch any exception from aggregation
                Exchange aggregatedExchange = aggregationStrategy.aggregate(exchange, resourceExchange);
                if (aggregatedExchange != null) {
                    if (ExchangeHelper.shouldSetVariableResult(aggregatedExchange, variableReceive)) {
                        // result should be stored in variable instead of message body
                        ExchangeHelper.setVariableFromMessageBodyAndHeaders(aggregatedExchange, variableReceive,
                                aggregatedExchange.getMessage());
                        aggregatedExchange.getMessage().setBody(originalBody);
                        aggregatedExchange.getMessage().setHeaders(originalHeaders);
                    }
                    // copy aggregation result onto original exchange (preserving pattern)
                    copyResultsPreservePattern(exchange, aggregatedExchange);
                    // handover any synchronization
                    if (resourceExchange != null) {
                        resourceExchange.getExchangeExtension().handoverCompletions(exchange);
                    }
                }
            }

            // if we failed then restore caused exception
            if (cause != null) {
                // restore caused exception
                exchange.setException(cause);
                // remove the exhausted marker as we want to be able to perform redeliveries with the error handler
                exchange.getExchangeExtension().setRedeliveryExhausted(false);

                // preserve the redelivery stats
                if (redelivered != null) {
                    exchange.getMessage().setHeader(Exchange.REDELIVERED, redelivered);
                }
                if (redeliveryCounter != null) {
                    exchange.getMessage().setHeader(Exchange.REDELIVERY_COUNTER, redeliveryCounter);
                }
                if (redeliveryMaxCounter != null) {
                    exchange.getMessage().setHeader(Exchange.REDELIVERY_MAX_COUNTER, redeliveryMaxCounter);
                }
            }

            // set property with the uri of the endpoint enriched so we can use that for tracing etc
            exchange.setProperty(ExchangePropertyKey.TO_ENDPOINT, consumer.getEndpoint().getEndpointUri());

        } catch (Exception e) {
            exchange.setException(new CamelExchangeException("Error occurred during aggregation", exchange, e));
            callback.done(true);
            return true;
        }

        callback.done(true);
        return true;
    }

    private static boolean isBridgeErrorHandler(PollingConsumer consumer) {
        Consumer delegate = consumer;
        if (consumer instanceof EventDrivenPollingConsumer eventDrivenPollingConsumer) {
            delegate = eventDrivenPollingConsumer.getDelegateConsumer();
        }

        // is the consumer bridging the error handler?
        boolean bridgeErrorHandler = false;
        if (delegate instanceof DefaultConsumer defaultConsumer) {
            ExceptionHandler handler = defaultConsumer.getExceptionHandler();
            if (handler instanceof BridgeExceptionHandlerToErrorHandler) {
                bridgeErrorHandler = true;
            }
        }
        return bridgeErrorHandler;
    }

    protected static Object prepareRecipient(Exchange exchange, Object recipient) throws NoTypeConversionAvailableException {
        return ProcessorHelper.prepareRecipient(exchange, recipient);
    }

    protected static Endpoint getExistingEndpoint(Exchange exchange, Object recipient) {
        return ProcessorHelper.getExistingEndpoint(exchange, recipient);
    }

    protected static Endpoint resolveEndpoint(Exchange exchange, Object recipient, boolean prototype) {
        return prototype
                ? ExchangeHelper.resolvePrototypeEndpoint(exchange, recipient)
                : ExchangeHelper.resolveEndpoint(exchange, recipient);
    }

    /**
     * Strategy to pre check polling.
     * <p/>
     * Is currently used to prevent doing poll enrich from a file based endpoint when the current route also started
     * from a file based endpoint as that is not currently supported.
     *
     * @param exchange the current exchange
     */
    protected void preCheckPoll(Exchange exchange) throws Exception {
        // noop
    }

    private static void prepareResult(Exchange exchange) {
        if (exchange.getPattern().isOutCapable()) {
            exchange.getOut().copyFrom(exchange.getIn());
        }
    }

    @Override
    public String toString() {
        return id;
    }

    protected static String resolveUri(Exchange exchange, Object recipient) throws NoTypeConversionAvailableException {
        if (recipient == null) {
            return null;
        }

        String uri;
        // trim strings as end users might have added spaces between separators
        if (recipient instanceof String string) {
            uri = string.trim();
        } else if (recipient instanceof Endpoint endpoint) {
            uri = endpoint.getEndpointKey();
        } else {
            // convert to a string type we can work with
            uri = exchange.getContext().getTypeConverter().mandatoryConvertTo(String.class, exchange, recipient);
        }

        // in case path has property placeholders then try to let property component resolve those
        try {
            uri = EndpointHelper.resolveEndpointUriPropertyPlaceholders(exchange.getContext(), uri);
        } catch (Exception e) {
            throw new ResolveEndpointFailedException(uri, e);
        }

        return uri;
    }

    protected static String resolveScheme(Exchange exchange, String uri) {
        return ExchangeHelper.resolveScheme(uri);
    }

    @Override
    protected void doBuild() throws Exception {
        if (consumerCache == null) {
            // create consumer cache if we use dynamic expressions for computing the endpoints to poll
            consumerCache = new DefaultConsumerCache(this, camelContext, cacheSize);
            LOG.debug("PollEnrich {} using ConsumerCache with cacheSize={}", this, cacheSize);
        }
        if (aggregationStrategy == null) {
            aggregationStrategy = new CopyAggregationStrategy();
        }
        CamelContextAware.trySetCamelContext(aggregationStrategy, camelContext);
        ServiceHelper.buildService(consumerCache, aggregationStrategy);
    }

    @Override
    protected void doInit() throws Exception {
        if (expression != null) {
            expression.init(camelContext);
        }

        if (isAutoStartupComponents() && uri != null) {
            // in case path has property placeholders then try to let property component resolve those
            String u = EndpointHelper.resolveEndpointUriPropertyPlaceholders(camelContext, uri);
            // find out which component it is
            scheme = ExchangeHelper.resolveScheme(u);
        }

        if (isAllowOptimisedComponents() && uri != null) {
            try {
                if (scheme != null) {
                    // find out if the component can be optimised for send-dynamic
                    PollDynamicAwareResolver resolver = new PollDynamicAwareResolver();
                    dynamicAware = resolver.resolve(camelContext, scheme);
                    if (dynamicAware == null) {
                        // okay fallback and try with default component name
                        Component comp = camelContext.getComponent(scheme, false, isAutoStartupComponents());
                        if (comp != null) {
                            String defaultScheme = comp.getDefaultName();
                            if (!scheme.equals(defaultScheme)) {
                                dynamicAware = resolver.resolve(camelContext, defaultScheme);
                                dynamicAware.setScheme(scheme);
                            }
                        }
                    }
                    if (dynamicAware != null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Detected PollDynamicAware component: {} optimising poll: {}", scheme,
                                    URISupport.sanitizeUri(uri));
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
                if (LOG.isDebugEnabled()) {
                    LOG.debug(
                            "Error creating optimised PollDynamicAwareResolver for uri: {} due to {}. This exception is ignored",
                            URISupport.sanitizeUri(uri), e.getMessage(), e);
                }
            }
        }

        headersMapFactory = camelContext.getCamelContextExtension().getHeadersMapFactory();

        ServiceHelper.initService(consumerCache, aggregationStrategy, dynamicAware);
    }

    @Override
    protected void doStart() throws Exception {
        // ensure the component is started
        if (autoStartupComponents && scheme != null) {
            camelContext.getComponent(scheme);
        }

        ServiceHelper.startService(consumerCache, aggregationStrategy, dynamicAware);
    }

    @Override
    protected void doStop() throws Exception {
        ServiceHelper.stopService(aggregationStrategy, consumerCache, dynamicAware);
    }

    @Override
    protected void doShutdown() throws Exception {
        ServiceHelper.stopAndShutdownServices(aggregationStrategy, consumerCache);
    }

    private static class CopyAggregationStrategy implements AggregationStrategy {

        @Override
        public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
            if (newExchange != null) {
                copyResultsPreservePattern(oldExchange, newExchange);
            } else {
                // if no newExchange then there was no message from the external resource,
                // and therefore we should set an empty body to indicate this fact
                // but keep headers/attachments as we want to propagate those
                oldExchange.getIn().setBody(null);
                oldExchange.setOut(null);
            }
            return oldExchange;
        }

    }

}
