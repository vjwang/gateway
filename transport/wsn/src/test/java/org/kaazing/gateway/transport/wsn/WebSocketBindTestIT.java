/**
 * Copyright (c) 2007-2014 Kaazing Corporation. All rights reserved.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.kaazing.gateway.transport.wsn;

import static org.junit.rules.RuleChain.outerRule;

import java.net.URI;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.kaazing.gateway.server.test.GatewayRule;
import org.kaazing.gateway.server.test.config.GatewayConfiguration;
import org.kaazing.gateway.server.test.config.builder.GatewayConfigurationBuilder;
import org.kaazing.k3po.junit.annotation.Specification;
import org.kaazing.k3po.junit.rules.K3poRule;

public class WebSocketBindTestIT {

	private final K3poRule robot = new K3poRule();

    private final GatewayRule gateway = new GatewayRule() {
        {
            // @formatter:off
            GatewayConfiguration configuration =
                    new GatewayConfigurationBuilder()
                        .service()
                            .accept(URI.create("ws://localhost:8001/echo"))
                            .accept(URI.create("ws://localhost:8001"))
                            .type("echo")
                            .crossOrigin()
                                .allowOrigin("*")
                            .done()
                        .done()
                    .done();
            // @formatter:on
            init(configuration);
        }
    };

    @Rule
    public TestRule chain = outerRule(robot).around(gateway);

    @Specification("canConnectUsingNormalWebSocketURI")
    @Test(timeout = 8000)
    public void canConnectUsingNormalWebSocketURI() throws Exception {
        robot.finish();
    }

    @Specification("canConnectUsingNormalWebSocketURIWithExtraSlashAfterEcho")
    @Test(timeout = 8000)
    public void canConnectUsingNormalWebSocketURIWithExtraSlash() throws Exception {
        robot.finish();
    }

    @Specification("canConnectUsingNormalWebSocketURIWithoutLoadBalancing")
    @Test(timeout = 8000)
    //TODO:KG-8523
    public void canConnectUsingNormalWebSocketURIWithoutLoadBalancing() throws Exception {
        robot.finish();
    }

    @Specification("cannotConnectUsingWebSocketURIWithExtraPathElement")
    @Test(timeout = 1500)
    public void cannotConnectUsingWebSocketURIWithExtraPathElement() throws Exception {
        robot.finish();
    }

    @Specification("canConnectUsingPathlessWebSocketURI")
    @Test(timeout = 8000)
    public void canConnectUsingPathlessWebSocketURI() throws Exception {
        robot.finish();
    }

    @Specification("canConnectUsingPathlessWebSocketURIWithExtraSlash")
    @Test(timeout = 8000)
    public void canConnectUsingPathlessWebSocketURIWithExtraSlash() throws Exception {
        robot.finish();
    }

    @Specification("cannotConnectToPathlessWebSocketURIWithExtraPathElement")
    @Test(timeout = 8000)
    public void cannotConnectToPathlessWebSocketURIWithExtraPathElement() throws Exception {
        robot.finish();
    }
}
