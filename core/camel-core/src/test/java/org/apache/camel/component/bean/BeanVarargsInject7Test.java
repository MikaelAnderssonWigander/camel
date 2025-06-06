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
package org.apache.camel.component.bean;

import java.util.Map;

import org.apache.camel.ContextTestSupport;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.spi.Registry;
import org.junit.jupiter.api.Test;

public class BeanVarargsInject7Test extends ContextTestSupport {

    private final MyBean myBean = new MyBean();

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            public void configure() {
                from("direct:start").to("bean:myBean?method=doSomething(${header.myArr},1,2,3)").to("mock:finish");
            }
        };
    }

    @Test
    public void testVarargs() throws Exception {
        MockEndpoint end = getMockEndpoint("mock:finish");
        end.expectedBodiesReceived("Bye Camel with 3 args (sum=6)");

        Object[] arr = new Object[] { "Cam", "el" };
        sendBody("direct:start", "World", Map.of("myArr", arr));

        assertMockEndpointsSatisfied();
    }

    @Override
    protected Registry createCamelRegistry() throws Exception {
        Registry answer = super.createCamelRegistry();
        answer.bind("myBean", myBean);
        return answer;
    }

    public static class MyBean {

        public String doSomething(Object[] arr, Object... args) {
            int sum = Integer.parseInt((String) args[0]);
            sum += Integer.parseInt((String) args[1]);
            sum += Integer.parseInt((String) args[2]);
            return "Bye " + arr[0] + arr[1] + " with " + args.length + " args (sum=" + sum + ")";
        }
    }
}
