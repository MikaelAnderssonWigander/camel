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
package org.apache.camel.component.jsonata;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import com.dashjoin.jsonata.Jsonata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Category;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.component.ResourceEndpoint;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.support.ExchangeHelper;
import org.apache.camel.util.ObjectHelper;

import static com.dashjoin.jsonata.Jsonata.jsonata;

/**
 * Transforms JSON payload using JSONata transformation.
 */
@UriEndpoint(firstVersion = "3.5.0", scheme = "jsonata", title = "JSONata", syntax = "jsonata:resourceUri",
             producerOnly = true, remote = false,
             category = { Category.TRANSFORMATION })
public class JsonataEndpoint extends ResourceEndpoint {

    private final ObjectMapper mapper = new ObjectMapper();

    @UriParam(defaultValue = "Jackson")
    private JsonataInputOutputType outputType;

    @UriParam(defaultValue = "Jackson")
    private JsonataInputOutputType inputType;

    @UriParam(label = "advanced", description = "To configure the Jsonata frame binding. Allows custom functions to be added.")
    private JsonataFrameBinding frameBinding;

    public JsonataEndpoint() {
    }

    public JsonataEndpoint(String uri, JsonataComponent component, String resourceUri, JsonataFrameBinding frameBinding) {
        super(uri, component, resourceUri);
        this.frameBinding = frameBinding;
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public ExchangePattern getExchangePattern() {
        return ExchangePattern.InOut;
    }

    @Override
    protected String createEndpointUri() {
        return "Jsonata:" + getResourceUri();
    }

    public JsonataInputOutputType getOutputType() {
        return outputType;
    }

    /**
     * Specifies if the output should be Jackson JsonNode or a JSON String.
     */
    public void setOutputType(JsonataInputOutputType outputType) {
        this.outputType = outputType;
    }

    public JsonataInputOutputType getInputType() {
        return inputType;
    }

    /**
     * Specifies if the input should be Jackson JsonNode or a JSON String.
     */
    public void setInputType(JsonataInputOutputType inputType) {
        this.inputType = inputType;
    }

    /**
     * Specifies the custom framebinding.
     */
    public void setFrameBinding(JsonataFrameBinding frameBinding) {
        this.frameBinding = frameBinding;
    }

    public JsonataFrameBinding getFrameBinding() {
        return frameBinding;
    }

    @Override
    protected void onExchange(Exchange exchange) throws Exception {
        String path = getResourceUri();
        ObjectHelper.notNull(path, "resourceUri");

        Map<String, Object> input;
        if (getInputType() == JsonataInputOutputType.JsonString) {
            InputStream inputStream = exchange.getIn().getBody(InputStream.class);
            input = mapper.readValue(inputStream, new TypeReference<>() {
            });
        } else {
            input = mapper.convertValue(exchange.getIn().getBody(), new TypeReference<>() {
            });
        }

        Jsonata expression = null;
        try (InputStreamReader inputStreamReader
                = new InputStreamReader(getResourceAsInputStream(), StandardCharsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader);) {
            String spec = bufferedReader
                    .lines()
                    .collect(Collectors.joining("\n"));
            expression = jsonata(spec);
        }

        Jsonata.Frame frame = expression.createFrame();
        if (frameBinding != null) {
            frameBinding.bindToFrame(frame);
        }
        Object outputLib = expression.evaluate(input, frame);
        String bodyAsString = mapper.writeValueAsString(outputLib);

        // now lets output the results to the exchange
        final Object output;
        if (getOutputType() == JsonataInputOutputType.JsonString) {
            output = bodyAsString;
        } else {
            output = mapper.readTree(bodyAsString);
        }
        ExchangeHelper.setInOutBodyPatternAware(exchange, output);
    }

}
