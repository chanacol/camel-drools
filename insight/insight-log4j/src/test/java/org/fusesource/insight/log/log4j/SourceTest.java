/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.insight.log.log4j;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 */
public class SourceTest {
    private Log4jLogQuery logQuery = new Log4jLogQuery();

    @Before
    public void init() {
        logQuery.start();
    }

    @After
    public void destroy() {
        logQuery.stop();
    }

    @Test
    public void testSourceDownload() throws Exception {
        String groupId = "org.apache.camel";
        String artifactId = "camel-core";
        String version = "2.10.3";
        String path = "/org/apache/camel/CamelContext.java";
        String content = logQuery.getSource(groupId, artifactId, version, path);
        System.out.println("Found content: " + content);
        String expectedContent = "CamelContext";
        assertTrue("content should contain '" + expectedContent + "'", content.indexOf(expectedContent) > 0);
    }
}